/*
 * The app's own JPEG codec, over libjpeg-turbo.
 *
 * Why this exists: the preview used to go through BitmapFactory and the files
 * the user edits were produced by Bitmap.compress, both of which are Skia's, so
 * the same bytes decode differently on different OEMs and Android versions - and
 * neither is the decoder the b_x4 sweep was measured with. Everything here is
 * one library at one version, so the app, the sweep and DHT.md agree.
 *
 * Two rules are baked in, both taken from what the sweep did:
 *
 *   1. **Standard Huffman tables.** optimize_coding is left off, so the file
 *      carries the Annex K tables the sweep's bit indices refer to. Turning it
 *      on would renumber every symbol and the classification would mean nothing.
 *   2. **Strict on damaged streams.** libjpeg calls error_exit on a corrupt
 *      stream; the default handler prints and calls exit(), so this installs one
 *      that longjmps out and reports failure. That is the behaviour the sweep
 *      recorded - the 630 bits it has no sample for are the ones this refuses -
 *      so a cell that is greyed out in the editor really is one that will not
 *      decode.
 *
 * Buffers live in a scratch struct rather than in locals, because a longjmp out
 * of libjpeg makes locals assigned after setjmp indeterminate - and freeing
 * garbage is a worse failure than the corrupt file that caused it.
 */

#include <jni.h>
#include <math.h>
#include <pthread.h>
#include <setjmp.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <jpeglib.h>

#if defined(__ARM_NEON) || defined(__ARM_NEON__)
#include <arm_neon.h>
#define JPEGPIPE_NEON 1
#else
#define JPEGPIPE_NEON 0
#endif

/* --- error handling ------------------------------------------------------- */

typedef struct {
  struct jpeg_error_mgr pub;
  jmp_buf jump;
  char message[JMSG_LENGTH_MAX];
} jpeg_error_ctx;

/* Every allocation an entry point makes, so the longjmp path can free them. */
typedef struct {
  unsigned char *rgb;
  unsigned char *upright;
  unsigned char *cropped;
  unsigned char *resized;
  unsigned char *encoded;
  unsigned long encoded_size;
  int width;
  int height;
  int upright_width;
  int upright_height;
} scratch;

static void jpeg_error_exit(j_common_ptr cinfo) {
  jpeg_error_ctx *ctx = (jpeg_error_ctx *)cinfo->err;
  (*cinfo->err->format_message)(cinfo, ctx->message);
  longjmp(ctx->jump, 1);
}

/* Warnings are not failures: a slightly odd file still decodes. */
static void jpeg_emit_message(j_common_ptr cinfo, int msg_level) {
  (void)cinfo;
  (void)msg_level;
}

static void jpeg_init_error(jpeg_error_ctx *ctx) {
  jpeg_std_error(&ctx->pub);
  ctx->pub.error_exit = jpeg_error_exit;
  ctx->pub.emit_message = jpeg_emit_message;
  ctx->message[0] = '\0';
}

static void scratch_free(scratch *s) {
  free(s->rgb);
  free(s->upright);
  free(s->cropped);
  free(s->resized);
  free(s->encoded);
  s->rgb = s->upright = s->cropped = s->resized = s->encoded = NULL;
}

/* --- decoding ------------------------------------------------------------- */

/*
 * Decode to 3-channel RGB, at an N/8 scale of the caller's choosing (8 = full).
 *
 * RGB rather than RGBA because that is what the encoder wants and what the
 * reference pipeline worked in; the ARGB conversion happens once, at the JNI
 * boundary, for the preview path only.
 */
static int decode_rgb(jpeg_error_ctx *ctx, scratch *s, const unsigned char *data, size_t len,
                      int scale) {
  struct jpeg_decompress_struct cinfo;
  memset(&cinfo, 0, sizeof cinfo);
  cinfo.err = &ctx->pub;
  jpeg_create_decompress(&cinfo);
  jpeg_mem_src(&cinfo, data, (unsigned long)len);
  if (jpeg_read_header(&cinfo, TRUE) != JPEG_HEADER_OK) {
    jpeg_destroy_decompress(&cinfo);
    return 0;
  }
  if (scale < 1) scale = 1;
  if (scale > 8) scale = 8;
  cinfo.scale_num = (unsigned int)scale;
  cinfo.scale_denom = 8;
  cinfo.out_color_space = JCS_RGB;
  jpeg_start_decompress(&cinfo);
  s->width = (int)cinfo.output_width;
  s->height = (int)cinfo.output_height;
  if (s->width <= 0 || s->height <= 0) {
    jpeg_destroy_decompress(&cinfo);
    return 0;
  }
  {
    const size_t stride = (size_t)s->width * (size_t)cinfo.output_components;
    s->rgb = (unsigned char *)malloc(stride * (size_t)s->height);
    if (s->rgb == NULL) {
      jpeg_destroy_decompress(&cinfo);
      return 0;
    }
    while (cinfo.output_scanline < cinfo.output_height) {
      unsigned char *row = s->rgb + (size_t)cinfo.output_scanline * stride;
      if (jpeg_read_scanlines(&cinfo, &row, 1) == 0) {
        jpeg_destroy_decompress(&cinfo);
        return 0;
      }
    }
    jpeg_finish_decompress(&cinfo);
    jpeg_destroy_decompress(&cinfo);
    return 1;
  }
}

/* The image's stored size, from the header alone. */
static int probe_size(jpeg_error_ctx *ctx, const unsigned char *data, size_t len, int *w,
                      int *h) {
  struct jpeg_decompress_struct cinfo;
  int ok = 0;
  memset(&cinfo, 0, sizeof cinfo);
  cinfo.err = &ctx->pub;
  jpeg_create_decompress(&cinfo);
  jpeg_mem_src(&cinfo, data, (unsigned long)len);
  if (jpeg_read_header(&cinfo, TRUE) == JPEG_HEADER_OK) {
    *w = (int)cinfo.image_width;
    *h = (int)cinfo.image_height;
    ok = 1;
  }
  jpeg_destroy_decompress(&cinfo);
  return ok;
}

/* --- coefficient usage ---------------------------------------------------- */

/*
 * Zigzag-order coefficient index -> row-major index in the 8x8 block, the same
 * table `QuantTables.ZIGZAG_ROW_MAJOR` uses on the Kotlin side. A DQT payload
 * lists quantizers in zigzag order while libjpeg hands coefficient blocks back
 * in row-major order, so this is what ties a quantizer byte to the coefficient
 * it scales.
 */
static const int zigzag_row_major[64] = {
    0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5,
    12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28,
    35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51,
    58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63,
};

/*
 * Which quantizers can change the picture, per quantization-table id.
 *
 * A quantizer scales its coefficient when the decoder dequantizes a block, so a
 * coefficient that is zero in every block stays zero no matter what the
 * quantizer says - editing that quantizer cannot move a pixel. This walks the
 * coefficient arrays libjpeg builds for the whole file (baseline and
 * progressive alike) and marks every coefficient that is non-zero somewhere.
 *
 * `out[table]` is a bitmask over zigzag coefficient indices. Returns 0 when the
 * stream does not decode, and the caller then has to assume every quantizer is
 * live rather than hide rows it cannot judge.
 */
static int coefficient_masks(jpeg_error_ctx *ctx, const unsigned char *data, size_t len,
                             unsigned long long out[4]) {
  struct jpeg_decompress_struct cinfo;
  jvirt_barray_ptr *coefs;
  int ok = 0;
  memset(out, 0, 4 * sizeof *out);
  memset(&cinfo, 0, sizeof cinfo);
  cinfo.err = &ctx->pub;
  jpeg_create_decompress(&cinfo);
  jpeg_mem_src(&cinfo, data, (unsigned long)len);
  if (jpeg_read_header(&cinfo, TRUE) != JPEG_HEADER_OK) {
    jpeg_destroy_decompress(&cinfo);
    return 0;
  }
  coefs = jpeg_read_coefficients(&cinfo);
  if (coefs != NULL) {
    for (int ci = 0; ci < cinfo.num_components; ci++) {
      jpeg_component_info *comp = &cinfo.comp_info[ci];
      const unsigned int table = (unsigned int)comp->quant_tbl_no & 3u;
      unsigned long long mask = 0;
      for (JDIMENSION row = 0; row < comp->height_in_blocks; row++) {
        JBLOCKARRAY blocks = (*cinfo.mem->access_virt_barray)(
            (j_common_ptr)&cinfo, coefs[ci], row, (JDIMENSION)1, FALSE);
        if (blocks == NULL) break;
        for (JDIMENSION col = 0; col < comp->width_in_blocks; col++) {
          const JCOEF *block = blocks[0][col];
          for (int k = 0; k < 64; k++) {
            if (block[zigzag_row_major[k]] != 0) mask |= 1ULL << k;
          }
        }
      }
      out[table] |= mask;
    }
    ok = 1;
  }
  jpeg_destroy_decompress(&cinfo);
  return ok;
}

/* --- encoding ------------------------------------------------------------- */

/*
 * Encode 3-channel RGB as a baseline JPEG into s->encoded.
 *
 * optimize_coding stays off so the Annex K tables are written, and no restart
 * markers are added, which is what makes the bytes comparable with the
 * reference pipeline's files.
 */
static int encode_rgb(jpeg_error_ctx *ctx, scratch *s, const unsigned char *rgb, int w, int h,
                      int quality, int subsampling) {
  struct jpeg_compress_struct cinfo;
  memset(&cinfo, 0, sizeof cinfo);
  cinfo.err = &ctx->pub;
  jpeg_create_compress(&cinfo);
  jpeg_mem_dest(&cinfo, &s->encoded, &s->encoded_size);
  cinfo.image_width = (JDIMENSION)w;
  cinfo.image_height = (JDIMENSION)h;
  cinfo.input_components = 3;
  cinfo.in_color_space = JCS_RGB;
  jpeg_set_defaults(&cinfo);
  jpeg_set_quality(&cinfo, quality, TRUE);
  cinfo.optimize_coding = FALSE;
  cinfo.restart_interval = 0;
  /* 0 = 4:4:4, 1 = 4:2:2, 2 = 4:2:0; the reference pipeline's files are 4:2:0. */
  {
    const int hv = subsampling == 2 ? 2 : 1;
    cinfo.comp_info[0].h_samp_factor = hv;
    cinfo.comp_info[0].v_samp_factor = hv;
    for (int i = 1; i < 3; i++) {
      cinfo.comp_info[i].h_samp_factor = 1;
      cinfo.comp_info[i].v_samp_factor = 1;
    }
  }
  jpeg_start_compress(&cinfo, TRUE);
  {
    const size_t stride = (size_t)w * 3;
    while (cinfo.next_scanline < cinfo.image_height) {
      unsigned char *row = (unsigned char *)rgb + (size_t)cinfo.next_scanline * stride;
      jpeg_write_scanlines(&cinfo, &row, 1);
    }
    jpeg_finish_compress(&cinfo);
    jpeg_destroy_compress(&cinfo);
  }
  return s->encoded != NULL && s->encoded_size > 0;
}

/* --- resampling ----------------------------------------------------------- */

/*
 * Lanczos-3, in the same fixed point Pillow's LANCZOS uses.
 *
 * The sweep resized with Pillow's LANCZOS and Skia's scaler is a different
 * filter, so the app has to do its own to see the pixels the classification was
 * measured on. Pillow works in fixed point - the coefficients are integers whose
 * sum is exactly 1 << PRECISION_BITS, accumulated in an int - and so does this,
 * which is why the two agree to a level or two rather than by luck.
 *
 * Destination pixel centres map to source coordinates as (x + 0.5) * scale - 0.5,
 * which is the mapping Pillow uses - anything else shifts the image by half a
 * pixel and would not line up with the reference.
 *
 * The weights depend only on the destination position, never on the pixel being
 * produced, so they are computed once per destination column (horizontal) or row
 * (vertical) and reused for every line. That is not a micro-optimisation: doing
 * it in the inner loop costs two sin() calls per tap per pixel, which is about 20
 * million transcendentals for one 2560x1920 -> 1400 pass.
 *
 * The inner loop is integer, and that is the point of the fixed point: a JPEG
 * has to come out the same on every device, and integer multiply-accumulate is
 * exactly reproducible, where a double sum invites a compiler to fuse it into an
 * FMA on arm64 and not on x86-64. It is also what makes the vector path below
 * possible at all - and because integer addition is exact, the vector and scalar
 * paths agree to the last bit rather than to within a tolerance.
 */
#define LANCZOS_SUPPORT 3.0

/*
 * Coefficient precision, in bits.
 *
 * The coefficients of a position are integers summing to exactly this, so the
 * accumulator peaks at 255 << LANCZOS_BITS times the kernel's absolute sum (about
 * 1.2x its signed one): 6.3 million at 14 bits, a factor of 340 inside an int.
 * Pillow uses 22, which is the most an int can hold with 8-bit samples; 14 is
 * what an int16 can hold, and that is what buys the vectorisation - eight
 * coefficients and eight samples multiply-accumulate in one instruction. The cost
 * is a coefficient error of about 1/16000 of a level, which the parity tests
 * against Pillow measure rather than assume.
 *
 * The other reason not to want more bits: every path here has to produce the
 * same pixels, and integer addition is exact and associative, so the vector and
 * scalar kernels below are bit-identical by construction. That is not true of any
 * floating-point version of this loop.
 */
#define LANCZOS_BITS 14
#define LANCZOS_ONE (1 << LANCZOS_BITS)

static double lanczos_weight(double x) {
  if (x == 0.0) return 1.0;
  if (x <= -LANCZOS_SUPPORT || x >= LANCZOS_SUPPORT) return 0.0;
  {
    const double px = 3.14159265358979323846 * x;
    return (LANCZOS_SUPPORT * sin(px) * sin(px / LANCZOS_SUPPORT)) / (px * px);
  }
}

/* The accumulated fixed-point sum as a byte, rounded once - Pillow's clip8. */
static unsigned char clip8(int v, int bits) {
  if (v <= 0) return 0;
  if (v >= 255 << bits) return 255;
  return (unsigned char)((v + (1 << (bits - 1))) >> bits);
}

/*
 * The resampling kernel of one axis, for one source and destination length.
 *
 * Position `p` reads source samples start[p] .. start[p] + count[p] - 1, whose
 * coefficients sit at weights + offset[p]. The coefficients of a position sum to
 * exactly LANCZOS_ONE, so no division is needed at the end. A position with no
 * taps (which needs a source of 0 length) is all zeros, i.e. a black pixel.
 *
 * The coefficients are int16 so that eight of them and eight samples can be
 * multiplied and accumulated in one Neon instruction; see the kernel functions
 * below, which fall back to the same arithmetic in scalar C everywhere else.
 */
typedef struct {
  int n;
  int *start;
  int *count;
  size_t *offset;
  short *weights;
  /*
   * The fixed-point scale this filter's coefficients were quantised to, and so
   * the shift the accumulator is finally reduced by. Only the scale is per
   * filter; the coefficients always sum to exactly 1 << bits, so no division is
   * needed at the end. See filter_build for why it is not always LANCZOS_BITS.
   */
  int bits;
} lanczos_filter;

static void filter_free(lanczos_filter *f) {
  free(f->start);
  free(f->count);
  free(f->offset);
  free(f->weights);
  memset(f, 0, sizeof *f);
}

static int filter_build(lanczos_filter *f, int src_len, int dst_len) {
  const double scale = (double)src_len / (double)dst_len;
  const double filter_scale = scale < 1.0 ? 1.0 : scale;
  const double support = LANCZOS_SUPPORT * filter_scale;
  size_t total = 0;
  int widest = 1;
  double *scratch = NULL;
  memset(f, 0, sizeof *f);
  f->n = dst_len;
  f->start = (int *)malloc(sizeof(int) * (size_t)dst_len);
  f->count = (int *)malloc(sizeof(int) * (size_t)dst_len);
  f->offset = (size_t *)malloc(sizeof(size_t) * (size_t)dst_len);
  if (f->start == NULL || f->count == NULL || f->offset == NULL) {
    filter_free(f);
    return 0;
  }
  for (int p = 0; p < dst_len; p++) {
    const double centre = (p + 0.5) * scale - 0.5;
    int left = (int)ceil(centre - support);
    int right = (int)floor(centre + support);
    if (left < 0) left = 0;
    if (right > src_len - 1) right = src_len - 1;
    f->start[p] = left;
    f->count[p] = right >= left ? right - left + 1 : 0;
    if (f->count[p] > widest) widest = f->count[p];
    f->offset[p] = total;
    total += (size_t)f->count[p];
  }
  f->weights = (short *)malloc(sizeof(short) * (total > 0 ? total : 1));
  scratch = (double *)malloc(sizeof(double) * (size_t)widest);
  if (f->weights == NULL || scratch == NULL) {
    free(scratch);
    filter_free(f);
    return 0;
  }

  /*
   * Quantise, at the finest scale whose coefficients fit an int16, and retry
   * coarser if they do not.
   *
   * Normalising by the sum is what Pillow does, and it is what makes this the
   * interpolating kernel rather than an approximation of one: where the sampled
   * kernel's sum is small the normalised coefficients are correspondingly large,
   * and at some phases - near a 1:1 ratio in particular - they legitimately
   * exceed an int16. The scale is therefore chosen from the worst position
   * instead of assumed. Because the coefficients still sum to exactly 1 << bits,
   * a coarser scale shrinks them by exactly as much as it grows the final shift,
   * and the accumulator stays bounded: 255 levels times the kernel's absolute
   * sum, which is ~1.2 x 1 << bits, so under 10 million either way.
   *
   * Treating those positions as unfilterable - which this did, to protect the
   * int16 - is what put a black column wherever the sum happened to cancel, and
   * those columns repeat with a period of 1/(ratio - 1) output pixels: a grid.
   */
  for (f->bits = LANCZOS_BITS; f->bits >= 4; f->bits--) {
    const double unit = (double)(1 << f->bits);
    int fits = 1;
    for (int p = 0; p < dst_len; p++) {
      const double centre = (p + 0.5) * scale - 0.5;
      short *coefs = f->weights + f->offset[p];
      const int count = f->count[p];
      int residue = 1 << f->bits;
      int largest = -1;
      int largest_at = 0;
      double sum = 0.0;
      for (int k = 0; k < count; k++) {
        scratch[k] = lanczos_weight(((double)(f->start[p] + k) - centre) / filter_scale);
        sum += scratch[k];
      }
      if (count == 0 || sum == 0.0) {
        /* Nothing to interpolate from: the position stays empty (black). */
        for (int k = 0; k < count; k++) coefs[k] = 0;
        continue;
      }
      for (int k = 0; k < count; k++) {
        const double scaled = scratch[k] / sum * unit;
        if (scaled >= 32767.0 || scaled <= -32768.0) {
          fits = 0;
          break;
        }
        const int coef = (int)(scaled < 0.0 ? scaled - 0.5 : scaled + 0.5);
        coefs[k] = (short)coef;
        residue -= coef;
        {
          const int magnitude = coef < 0 ? -coef : coef;
          if (magnitude > largest) {
            largest = magnitude;
            largest_at = k;
          }
        }
      }
      if (!fits) break;
      /* Keep the sum exactly 1 << bits, on the coefficient that moves least. */
      coefs[largest_at] = (short)(coefs[largest_at] + residue);
    }
    if (fits) break;
  }

  free(scratch);
  return 1;
}

/* --- planes --------------------------------------------------------------- */
/*
 * The passes work on one channel at a time instead of interleaved RGB.
 *
 * That is what makes them vectorisable: in a plane, the taps of a horizontal
 * pass are contiguous bytes, and the samples of a vertical pass are a contiguous
 * row. It costs a de-interleave per source row and an interleave per destination
 * row, both of which are one vld3/vst3, and it leaves the memory footprint where
 * it was: only the intermediate (tw x h) is held as planes.
 *
 * PLANE_PAD is slack so a vector load may run a few bytes past the last row
 * without a branch; the loads are in bounds by construction (a block is only
 * entered when its whole eight samples are inside the row), and this is the
 * margin that keeps that true if the loops are ever rearranged.
 */
#define PLANE_PAD 16

static unsigned char *plane_alloc(size_t samples) {
  unsigned char *plane = (unsigned char *)malloc(samples + PLANE_PAD);
  if (plane != NULL) memset(plane + samples, 0, PLANE_PAD);
  return plane;
}

/* One interleaved source row as three contiguous planes of [w] samples. */
static void row_to_planes(const unsigned char *row, int w, unsigned char *const p[3]) {
  int x = 0;
#if JPEGPIPE_NEON
  for (; x + 16 <= w; x += 16) {
    const uint8x16x3_t v = vld3q_u8(row + (size_t)x * 3);
    vst1q_u8(p[0] + x, v.val[0]);
    vst1q_u8(p[1] + x, v.val[1]);
    vst1q_u8(p[2] + x, v.val[2]);
  }
#endif
  for (; x < w; x++) {
    p[0][x] = row[(size_t)x * 3];
    p[1][x] = row[(size_t)x * 3 + 1];
    p[2][x] = row[(size_t)x * 3 + 2];
  }
}

/* --- the two passes ------------------------------------------------------- */

#if JPEGPIPE_NEON
/* The four accumulator lanes as one value. */
static int horizontal_sum(int32x4_t v) {
#if defined(__aarch64__)
  return vaddvq_s32(v);
#else
  const int32x2_t pair = vadd_s32(vget_low_s32(v), vget_high_s32(v));
  return vget_lane_s32(vpadd_s32(pair, pair), 0);
#endif
}
#endif

/*
 * One output sample of a horizontal pass: the dot product of `count`
 * coefficients with `count` contiguous source samples.
 *
 * Eight taps at a time, then four, then scalar - the remainders and the tail are
 * what this loop is made of in practice, since a downscale of 1.83x (a 2560-wide
 * photo previewed at 1400) has twelve taps and a downscale of 2x has thirteen.
 * Leaving four of those taps to scalar C measured as no speed-up at all: the
 * block was 8 of 12 taps and the horizontal reduction was seven instructions per
 * output sample, so the win went into the overhead.
 *
 * Integer accumulation is exact and associative, so this returns the same value
 * as the scalar loop to the last bit, whatever order the hardware adds them in.
 */
static int dot_plane(const unsigned char *samples, const short *coefs, int count) {
  int acc = 0;
  int k = 0;
#if JPEGPIPE_NEON
  int32x4_t accv = vdupq_n_s32(0);
  for (; k + 8 <= count; k += 8) {
    const int16x8_t s = vreinterpretq_s16_u16(vmovl_u8(vld1_u8(samples + k)));
    const int16x8_t c = vld1q_s16(coefs + k);
    accv = vmlal_s16(accv, vget_low_s16(s), vget_low_s16(c));
    accv = vmlal_s16(accv, vget_high_s16(s), vget_high_s16(c));
  }
  if (k + 4 <= count) {
    /* The row planes carry PLANE_PAD slack, so the eight-byte load is in bounds
     * even though only four of its samples are taps. */
    const int16x8_t s = vreinterpretq_s16_u16(vmovl_u8(vld1_u8(samples + k)));
    accv = vmlal_s16(accv, vget_low_s16(s), vld1_s16(coefs + k));
    k += 4;
  }
  acc = horizontal_sum(accv);
#endif
  for (; k < count; k++) acc += coefs[k] * samples[k];
  return acc;
}

/* One source row of one channel -> one destination row, tw samples. */
static void resample_horizontal_row(const unsigned char *src, const lanczos_filter *f,
                                    unsigned char *dst) {
  for (int x = 0; x < f->n; x++) {
    dst[x] = clip8(dot_plane(src + f->start[x], f->weights + f->offset[x], f->count[x]),
                   f->bits);
  }
}

#if JPEGPIPE_NEON
/*
 * int32 accumulators -> bytes, with the rounding and saturation clip8 applies.
 *
 * The scale is a filter property, not a constant, so the shift is by a register
 * rather than by an immediate: VSHL with a negative count is an arithmetic right
 * shift, and the two saturating narrows are clip8's clamps.
 */
static uint8x8_t narrow8(int32x4_t lo, int32x4_t hi, int bits) {
  const int32x4_t rounding = vdupq_n_s32(1 << (bits - 1));
  const int32x4_t shift = vdupq_n_s32(-bits);
  const int16x8_t rounded = vcombine_s16(
      vqmovn_s32(vshlq_s32(vaddq_s32(lo, rounding), shift)),
      vqmovn_s32(vshlq_s32(vaddq_s32(hi, rounding), shift)));
  return vqmovun_s16(rounded);
}
#endif

/*
 * One destination row of the vertical pass, written interleaved.
 *
 * The vector loop is over destination pixels rather than over taps: the
 * coefficient is a broadcast and the source rows are contiguous loads, which is
 * what the hardware wants. Three channels are accumulated in parallel, so the
 * row is written out with one vst3.
 */
static void resample_vertical_row(const unsigned char *const src[3], int w,
                                  const lanczos_filter *f, int y, unsigned char *out) {
  const int count = f->count[y];
  const int start = f->start[y];
  const short *coefs = f->weights + f->offset[y];
  const unsigned char *r0;
  const unsigned char *r1;
  const unsigned char *r2;
  int x = 0;

  if (count == 0) {
    memset(out, 0, (size_t)w * 3);
    return;
  }
  r0 = src[0] + (size_t)start * (size_t)w;
  r1 = src[1] + (size_t)start * (size_t)w;
  r2 = src[2] + (size_t)start * (size_t)w;

#if JPEGPIPE_NEON
  {
    for (; x + 8 <= w; x += 8) {
      int32x4_t a0 = vdupq_n_s32(0), a1 = vdupq_n_s32(0);
      int32x4_t b0 = vdupq_n_s32(0), b1 = vdupq_n_s32(0);
      int32x4_t c0 = vdupq_n_s32(0), c1 = vdupq_n_s32(0);
      for (int k = 0; k < count; k++) {
        const int16x4_t coef = vdup_n_s16(coefs[k]);
        const unsigned char *row0 = r0 + (size_t)k * (size_t)w + x;
        const unsigned char *row1 = r1 + (size_t)k * (size_t)w + x;
        const unsigned char *row2 = r2 + (size_t)k * (size_t)w + x;
        const int16x8_t s0 = vreinterpretq_s16_u16(vmovl_u8(vld1_u8(row0)));
        const int16x8_t s1 = vreinterpretq_s16_u16(vmovl_u8(vld1_u8(row1)));
        const int16x8_t s2 = vreinterpretq_s16_u16(vmovl_u8(vld1_u8(row2)));
        a0 = vmlal_s16(a0, vget_low_s16(s0), coef);
        a1 = vmlal_s16(a1, vget_high_s16(s0), coef);
        b0 = vmlal_s16(b0, vget_low_s16(s1), coef);
        b1 = vmlal_s16(b1, vget_high_s16(s1), coef);
        c0 = vmlal_s16(c0, vget_low_s16(s2), coef);
        c1 = vmlal_s16(c1, vget_high_s16(s2), coef);
      }
      {
        const uint8x8x3_t px = {
            narrow8(a0, a1, f->bits),
            narrow8(b0, b1, f->bits),
            narrow8(c0, c1, f->bits),
        };
        vst3_u8(out + (size_t)x * 3, px);
      }
    }
  }
#endif

  for (; x < w; x++) {
    int acc0 = 0, acc1 = 0, acc2 = 0;
    for (int k = 0; k < count; k++) {
      const int coef = coefs[k];
      const size_t at = (size_t)k * (size_t)w + (size_t)x;
      acc0 += coef * r0[at];
      acc1 += coef * r1[at];
      acc2 += coef * r2[at];
    }
    out[(size_t)x * 3] = clip8(acc0, f->bits);
    out[(size_t)x * 3 + 1] = clip8(acc1, f->bits);
    out[(size_t)x * 3 + 2] = clip8(acc2, f->bits);
  }
}

/* --- splitting the work --------------------------------------------------- */

/*
 * Both passes are functions of disjoint destination rows, so a resample of a
 * phone photo can use the phone's other cores - which is where most of the
 * remaining time was going: one core worked while the rest idled.
 *
 * The rows are handed out in slices rather than in one block per thread, because
 * a phone's cores are not equal and the scheduler will not necessarily give the
 * busy threads the fast ones. A Pixel 7a, for instance, runs this app in a cpuset
 * of two 2.35 GHz cores and four 1.8 GHz ones: with a fixed four-way split the
 * whole resample finishes when the two slowest quarters do, and the fast cores
 * sit idle for the rest. Four slices per thread shrink that to a quarter of a
 * slice, for a few instructions of atomic per slice.
 *
 * Two rules keep this honest. Each slice computes exactly the rows it would have
 * computed anyway, with the same arithmetic, so the pixels do not depend on how
 * many threads ran or in what order they took their work; and enough work has to
 * be on the table for threads to be worth their own cost, so a small image (which
 * is every parity fixture) stays single-threaded and deterministic right down to
 * the instructions executed.
 */
#define RESAMPLE_MAX_THREADS 4
#define RESAMPLE_SLICES_PER_THREAD 4
#define RESAMPLE_MIN_ROWS 16
#define RESAMPLE_MIN_WORK 1000000

typedef void (*row_fn)(void *ctx, int thread, int begin, int end);

typedef struct {
  row_fn fn;
  void *ctx;
  int rows;
  int slices;
  int next; /* atomic: the next slice to claim */
} row_pool;

typedef struct {
  row_pool *pool;
  int thread; /* which thread's scratch to use */
} row_worker;

/* How many threads to use for `rows` rows of `samples_per_row` work each. */
static int worker_count(int rows, long long samples_per_row) {
  long cores;
  int threads;
  if (rows < RESAMPLE_MIN_ROWS) return 1;
  if (samples_per_row <= 0 || (long long)rows * samples_per_row < RESAMPLE_MIN_WORK) return 1;
  cores = sysconf(_SC_NPROCESSORS_ONLN);
  if (cores < 2) return 1;
  threads = cores > RESAMPLE_MAX_THREADS ? RESAMPLE_MAX_THREADS : (int)cores;
  if (threads > rows / 2) threads = rows / 2;
  return threads < 1 ? 1 : threads;
}

static int slice_begin(int rows, int slices, int slice) {
  return (int)((long long)rows * slice / slices);
}

static void *row_worker_main(void *arg) {
  row_worker *worker = (row_worker *)arg;
  row_pool *pool = worker->pool;
  for (;;) {
    const int slice = __atomic_fetch_add(&pool->next, 1, __ATOMIC_RELAXED);
    if (slice >= pool->slices) break;
    pool->fn(pool->ctx, worker->thread, slice_begin(pool->rows, pool->slices, slice),
             slice_begin(pool->rows, pool->slices, slice + 1));
  }
  return NULL;
}

static void run_rows(int rows, long long samples_per_row, row_fn fn, void *ctx) {
  row_pool pool;
  row_worker workers[RESAMPLE_MAX_THREADS];
  pthread_t ids[RESAMPLE_MAX_THREADS];
  int started[RESAMPLE_MAX_THREADS];
  const int threads = worker_count(rows, samples_per_row);
  int i;

  if (threads <= 1) {
    fn(ctx, 0, 0, rows);
    return;
  }
  pool.fn = fn;
  pool.ctx = ctx;
  pool.rows = rows;
  pool.slices = threads * RESAMPLE_SLICES_PER_THREAD;
  if (pool.slices > rows) pool.slices = rows;
  pool.next = 0;
  for (i = 0; i < threads; i++) {
    workers[i].pool = &pool;
    workers[i].thread = i;
    started[i] = 0;
  }
  /* This thread takes part too, after the others have been given their start. */
  for (i = 1; i < threads; i++) {
    if (pthread_create(&ids[i], NULL, row_worker_main, &workers[i]) == 0) {
      started[i] = 1;
    }
  }
  row_worker_main(&workers[0]);
  for (i = 1; i < threads; i++) {
    if (started[i]) pthread_join(ids[i], NULL);
  }
}

/* --- the stages ----------------------------------------------------------- */

/* The horizontal pass over a range of source rows, one row at a time. */
typedef struct {
  const unsigned char *src;
  const lanczos_filter *filter;
  int w;
  int tw;
  unsigned char *const *mid;
  unsigned char *row; /* RESAMPLE_MAX_THREADS sets of three row planes */
} horizontal_ctx;

static void horizontal_rows(void *ctx, int thread, int begin, int end) {
  horizontal_ctx *h = (horizontal_ctx *)ctx;
  unsigned char *row[3];
  row[0] = h->row + (size_t)thread * 3 * (size_t)(h->w + PLANE_PAD);
  row[1] = row[0] + (h->w + PLANE_PAD);
  row[2] = row[1] + (h->w + PLANE_PAD);
  for (int y = begin; y < end; y++) {
    row_to_planes(h->src + (size_t)y * (size_t)h->w * 3, h->w, row);
    for (int c = 0; c < 3; c++) {
      resample_horizontal_row(row[c], h->filter, h->mid[c] + (size_t)y * (size_t)h->tw);
    }
  }
}

/* The vertical pass over a range of destination rows, three channels at once. */
typedef struct {
  const unsigned char *const *mid;
  const lanczos_filter *filter;
  int w;
  unsigned char *out;
} vertical_ctx;

static void vertical_rows(void *ctx, int thread, int begin, int end) {
  vertical_ctx *v = (vertical_ctx *)ctx;
  (void)thread;
  for (int y = begin; y < end; y++) {
    resample_vertical_row(v->mid, v->w, v->filter, y, v->out + (size_t)y * (size_t)v->w * 3);
  }
}

/* RGB (w x h) -> RGB (tw x th) into s->resized. */
static int resize_lanczos(scratch *s, const unsigned char *src, int w, int h, int tw, int th) {
  lanczos_filter fh, fv;
  horizontal_ctx hctx;
  vertical_ctx vctx;
  unsigned char *mid[3] = {NULL, NULL, NULL};
  unsigned char *row = NULL;
  const size_t plane = (size_t)tw * (size_t)h;
  const size_t row_bytes = (size_t)(w + PLANE_PAD);
  int ok = 0;
  if (w <= 0 || h <= 0 || tw <= 0 || th <= 0) return 0;
  memset(&fh, 0, sizeof fh);
  memset(&fv, 0, sizeof fv);
  memset(&hctx, 0, sizeof hctx);
  memset(&vctx, 0, sizeof vctx);
  s->resized = (unsigned char *)malloc((size_t)tw * (size_t)th * 3);
  if (s->resized == NULL) return 0;
  row = (unsigned char *)calloc((size_t)RESAMPLE_MAX_THREADS * 3, row_bytes);
  if (row == NULL) goto done;
  for (int c = 0; c < 3; c++) {
    mid[c] = plane_alloc(plane);
    if (mid[c] == NULL) goto done;
  }
  if (!filter_build(&fh, w, tw) || !filter_build(&fv, h, th)) goto done;

  hctx.src = src;
  hctx.filter = &fh;
  hctx.w = w;
  hctx.tw = tw;
  hctx.mid = mid;
  hctx.row = row;
  run_rows(h, (long long)tw * 3, horizontal_rows, &hctx);

  vctx.mid = mid;
  vctx.filter = &fv;
  vctx.w = tw;
  vctx.out = s->resized;
  run_rows(th, (long long)tw * 3, vertical_rows, &vctx);
  ok = 1;

done:
  free(row);
  for (int c = 0; c < 3; c++) free(mid[c]);
  filter_free(&fh);
  filter_free(&fv);
  if (!ok) {
    free(s->resized);
    s->resized = NULL;
  }
  return ok;
}

/*
 * The stored pixel grid turned the way up the EXIF orientation tag says it is.
 *
 * libjpeg neither reads that tag nor applies it: it hands back the grid the camera
 * wrote, and only the tag says which way round that grid is. Every other consumer of
 * these bytes - the app's own previews, and Android itself - honours the tag, so a
 * file written out of this pipeline, which carries no EXIF of its own, has to carry
 * the rotation in its pixels or it is the same picture lying on its side.
 *
 * `orientation` is the EXIF value, the numbers `ExifInterface.ORIENTATION_*` uses:
 * 1 normal, 2 flip horizontally, 3 rotate 180, 4 flip vertically, 5 transpose,
 * 6 rotate 90 clockwise, 7 transverse, 8 rotate 270 clockwise. 5-8 swap the axes, so
 * the result is `height` wide and `width` high. Only 2-8 are worth calling this for
 * (1 means the grid is already the picture) and anything else is refused. Returns 0
 * when it cannot do the job - no buffer, or an orientation outside 2-8 - and the
 * caller then gives up on the encode rather than writing the sideways file.
 */
static int orient_upright(scratch *s, int orientation) {
  const int w = s->width;
  const int h = s->height;
  const int swap = orientation >= 5;
  const int dw = swap ? h : w;
  const int dh = swap ? w : h;
  unsigned char *dst;
  if (w <= 0 || h <= 0) return 0;
  if (orientation < 2 || orientation > 8) return 0;
  dst = (unsigned char *)malloc((size_t)dw * (size_t)dh * 3);
  if (dst == NULL) return 0;
  for (int y = 0; y < dh; y++) {
    unsigned char *out = dst + (size_t)y * (size_t)dw * 3;
    switch (orientation) {
      case 2: { /* flip horizontally: row y, read right to left */
        const unsigned char *src = s->rgb + (size_t)y * (size_t)w * 3;
        for (int x = 0; x < dw; x++) {
          memcpy(out + (size_t)x * 3, src + (size_t)(w - 1 - x) * 3, 3);
        }
        break;
      }
      case 3: { /* rotate 180: row h-1-y, read right to left */
        const unsigned char *src = s->rgb + (size_t)(h - 1 - y) * (size_t)w * 3;
        for (int x = 0; x < dw; x++) {
          memcpy(out + (size_t)x * 3, src + (size_t)(w - 1 - x) * 3, 3);
        }
        break;
      }
      case 4: { /* flip vertically: row h-1-y, read left to right */
        const unsigned char *src = s->rgb + (size_t)(h - 1 - y) * (size_t)w * 3;
        memcpy(out, src, (size_t)w * 3);
        break;
      }
      case 5: /* transpose: dst row y is source column y, read top to bottom */
        for (int x = 0; x < dw; x++) {
          memcpy(out + (size_t)x * 3,
                 s->rgb + ((size_t)x * (size_t)w + (size_t)y) * 3, 3);
        }
        break;
      case 6: /* rotate 90 clockwise: source column y, read bottom to top */
        for (int x = 0; x < dw; x++) {
          memcpy(out + (size_t)x * 3,
                 s->rgb + ((size_t)(h - 1 - x) * (size_t)w + (size_t)y) * 3, 3);
        }
        break;
      case 7: /* transverse: source column w-1-y, read bottom to top */
        for (int x = 0; x < dw; x++) {
          memcpy(out + (size_t)x * 3,
                 s->rgb + ((size_t)(h - 1 - x) * (size_t)w + (size_t)(w - 1 - y)) * 3, 3);
        }
        break;
      default: /* 8, rotate 270 clockwise: source column w-1-y, top to bottom */
        for (int x = 0; x < dw; x++) {
          memcpy(out + (size_t)x * 3,
                 s->rgb + ((size_t)x * (size_t)w + (size_t)(w - 1 - y)) * 3, 3);
        }
        break;
    }
  }
  s->upright = dst;
  s->upright_width = dw;
  s->upright_height = dh;
  return 1;
}

/* --- JNI ------------------------------------------------------------------ */

/* 0xAARRGGBB pixels as the 3-channel RGB the resampler and encoder work in. */
static unsigned char *argb_to_rgb(const jint *pixels, int w, int h) {
  unsigned char *rgb = (unsigned char *)malloc((size_t)w * (size_t)h * 3);
  if (rgb == NULL) return NULL;
  for (int y = 0; y < h; y++) {
    unsigned char *dst = rgb + (size_t)y * (size_t)w * 3;
    const jint *src = pixels + (size_t)y * (size_t)w;
    for (int x = 0; x < w; x++) {
      const jint p = src[x];
      dst[x * 3] = (unsigned char)((p >> 16) & 0xFF);
      dst[x * 3 + 1] = (unsigned char)((p >> 8) & 0xFF);
      dst[x * 3 + 2] = (unsigned char)(p & 0xFF);
    }
  }
  return rgb;
}

/* RGB pixels as a Java int array, with the size written to out_size. */
static jintArray rgb_to_java(JNIEnv *env, const unsigned char *rgb, int w, int h,
                             jintArray out_size) {
  jintArray argb = (*env)->NewIntArray(env, (jsize)((size_t)w * (size_t)h));
  jint *row_ints;
  if (argb == NULL) return NULL;
  row_ints = (jint *)malloc(sizeof(jint) * (size_t)w);
  if (row_ints == NULL) return NULL;
  for (int y = 0; y < h; y++) {
    const unsigned char *src = rgb + (size_t)y * (size_t)w * 3;
    for (int x = 0; x < w; x++) {
      row_ints[x] = (jint)(0xFF000000u | ((unsigned int)src[x * 3] << 16) |
                           ((unsigned int)src[x * 3 + 1] << 8) |
                           (unsigned int)src[x * 3 + 2]);
    }
    (*env)->SetIntArrayRegion(env, argb, (jsize)((size_t)y * (size_t)w), (jsize)w, row_ints);
  }
  free(row_ints);
  if (out_size != NULL && (*env)->GetArrayLength(env, out_size) >= 2) {
    jint size[2] = {(jint)w, (jint)h};
    (*env)->SetIntArrayRegion(env, out_size, 0, 2, size);
  }
  return argb;
}

/*
 * Crop and Lanczos-resize pixels the caller already has.
 *
 * The ResizeScreen previews the crop and scale it is about to apply, and that
 * preview has to be the resampler the export will use - otherwise the picture on
 * screen is not the picture in the file, which is the whole complaint this
 * pipeline exists to answer.
 *
 * The caller cannot work the result's size out for itself, which is why [out_size]
 * is here: a crop changes both dimensions, and a target width above the *cropped*
 * width leaves the size alone rather than scaling it up. Guessing from the
 * uncropped dimensions gets both of those wrong - it read rows of a 9:16 crop at
 * a 4:3 stride, and asked for a fallback to the platform scaler on the sizes
 * where the guess exceeded what was returned.
 */
JNIEXPORT jintArray JNICALL
Java_ca_justinmo_glitch_jpeg_JpegNative_scalePixelsArgb(JNIEnv *env, jobject thiz,
                                                        jintArray argb, jint width, jint height,
                                                        jint target_width, jfloat crop_aspect,
                                                        jintArray out_size) {
  jintArray result = NULL;
  jint *pixels;
  unsigned char *mid = NULL;
  unsigned char *out = NULL;
  const unsigned char *source;
  int source_w, source_h, tw, th;
  (void)thiz;
  if (width <= 0 || height <= 0) return NULL;
  pixels = (*env)->GetIntArrayElements(env, argb, NULL);
  if (pixels == NULL) return NULL;

  mid = argb_to_rgb(pixels, width, height);
  if (mid != NULL) {
    source = mid;
    source_w = width;
    source_h = height;

    if (crop_aspect > 0.0f) {
      int cw = width;
      int ch = height;
      const double current = (double)width / (double)height;
      if (current > (double)crop_aspect) {
        cw = (int)((double)height * (double)crop_aspect + 0.5);
      } else {
        ch = (int)((double)width / (double)crop_aspect + 0.5);
      }
      if (cw < 1) cw = 1;
      if (ch < 1) ch = 1;
      if (cw < width || ch < height) {
        const int left = (width - cw) / 2;
        const int top = (height - ch) / 2;
        out = (unsigned char *)malloc((size_t)cw * (size_t)ch * 3);
        if (out != NULL) {
          for (int y = 0; y < ch; y++) {
            memcpy(out + (size_t)y * (size_t)cw * 3,
                   mid + ((size_t)(top + y) * (size_t)width + (size_t)left) * 3,
                   (size_t)cw * 3);
          }
          source = out;
          source_w = cw;
          source_h = ch;
        }
      }
    }

    tw = source_w;
    th = source_h;
    if (target_width > 0 && target_width < source_w) {
      scratch s;
      memset(&s, 0, sizeof s);
      th = (int)((double)source_h * (double)target_width / (double)source_w + 0.5);
      if (resize_lanczos(&s, source, source_w, source_h, target_width, th)) {
        result = rgb_to_java(env, s.resized, target_width, th, out_size);
      }
      scratch_free(&s);
    } else {
      result = rgb_to_java(env, source, tw, th, out_size);
    }
  }

  free(mid);
  free(out);
  (*env)->ReleaseIntArrayElements(env, argb, pixels, JNI_ABORT);
  return result;
}

JNIEXPORT jstring JNICALL
Java_ca_justinmo_glitch_jpeg_JpegNative_version(JNIEnv *env, jobject thiz) {
  char buf[96];
  (void)thiz;
#ifdef LIBJPEG_TURBO_VERSION
#define JPEGPIPE_STR_(x) #x
#define JPEGPIPE_STR(x) JPEGPIPE_STR_(x)
  snprintf(buf, sizeof buf, "%s", JPEGPIPE_STR(LIBJPEG_TURBO_VERSION));
#else
  snprintf(buf, sizeof buf, "libjpeg %d", JPEG_LIB_VERSION);
#endif
  return (*env)->NewStringUTF(env, buf);
}

/*
 * Per-table coefficient masks: index `table id` -> bitmask of zigzag
 * coefficients that are non-zero in at least one block of a component using
 * that table. Returns NULL when the library cannot decode the file.
 */
JNIEXPORT jlongArray JNICALL
Java_ca_justinmo_glitch_jpeg_JpegNative_coefficientMasks(JNIEnv *env, jobject thiz,
                                                         jbyteArray jpeg_bytes) {
  jlongArray result = NULL;
  jpeg_error_ctx ctx;
  unsigned long long masks[4];
  jsize len = (*env)->GetArrayLength(env, jpeg_bytes);
  jbyte *bytes;
  (void)thiz;
  bytes = (*env)->GetByteArrayElements(env, jpeg_bytes, NULL);
  if (bytes == NULL) return NULL;
  jpeg_init_error(&ctx);

  if (setjmp(ctx.jump) == 0) {
    if (coefficient_masks(&ctx, (const unsigned char *)bytes, (size_t)len, masks)) {
      jlong values[4] = {
          (jlong)masks[0], (jlong)masks[1], (jlong)masks[2], (jlong)masks[3]};
      result = (*env)->NewLongArray(env, 4);
      if (result != NULL) {
        (*env)->SetLongArrayRegion(env, result, 0, 4, values);
      }
    }
  }

  (*env)->ReleaseByteArrayElements(env, jpeg_bytes, bytes, JNI_ABORT);
  return result;
}

/*
 * Decode to 0xAARRGGBB ints, scaled so the longest edge is at most maxDim.
 *
 * The scale is a libjpeg N/8 factor, which is how a preview of a 12 MP photo is
 * decoded without ever materialising 48 MB of pixels in Java. maxDim <= 0 means
 * full size. Returns NULL when the stream does not decode.
 */
JNIEXPORT jintArray JNICALL
Java_ca_justinmo_glitch_jpeg_JpegNative_decodeArgb(JNIEnv *env, jobject thiz,
                                                   jbyteArray jpeg_bytes, jint max_dim,
                                                   jintArray out_size) {
  jintArray result = NULL;
  jpeg_error_ctx ctx;
  scratch s;
  jsize len = (*env)->GetArrayLength(env, jpeg_bytes);
  jbyte *bytes;
  (void)thiz;
  memset(&s, 0, sizeof s);
  bytes = (*env)->GetByteArrayElements(env, jpeg_bytes, NULL);
  if (bytes == NULL) return NULL;
  jpeg_init_error(&ctx);

  if (setjmp(ctx.jump) == 0) {
    int scale = 8;
    if (max_dim > 0) {
      int w = 0, h = 0;
      if (probe_size(&ctx, (const unsigned char *)bytes, (size_t)len, &w, &h)) {
        const long longest = w > h ? w : h;
        while (scale > 1 && longest * scale / 8 > (long)max_dim) scale--;
      }
    }
    if (decode_rgb(&ctx, &s, (const unsigned char *)bytes, (size_t)len, scale)) {
      jintArray pixels = (*env)->NewIntArray(env, (jsize)((size_t)s.width * (size_t)s.height));
      if (pixels != NULL) {
        jint *row_ints = (jint *)malloc(sizeof(jint) * (size_t)s.width);
        if (row_ints != NULL) {
          for (int y = 0; y < s.height; y++) {
            const unsigned char *src = s.rgb + (size_t)y * (size_t)s.width * 3;
            for (int x = 0; x < s.width; x++) {
              row_ints[x] = (jint)(0xFF000000u | ((unsigned int)src[x * 3] << 16) |
                                   ((unsigned int)src[x * 3 + 1] << 8) |
                                   (unsigned int)src[x * 3 + 2]);
            }
            (*env)->SetIntArrayRegion(env, pixels, (jsize)((size_t)y * (size_t)s.width),
                                      (jsize)s.width, row_ints);
          }
          free(row_ints);
          if (out_size != NULL && (*env)->GetArrayLength(env, out_size) >= 2) {
            jint size[2] = {(jint)s.width, (jint)s.height};
            (*env)->SetIntArrayRegion(env, out_size, 0, 2, size);
          }
          result = pixels;
        }
      }
    }
  }

  scratch_free(&s);
  (*env)->ReleaseByteArrayElements(env, jpeg_bytes, bytes, JNI_ABORT);
  return result;
}

/* Encode 0xAARRGGBB ints: quality and subsampling from the caller. */
JNIEXPORT jbyteArray JNICALL
Java_ca_justinmo_glitch_jpeg_JpegNative_encodeArgb(JNIEnv *env, jobject thiz, jintArray argb,
                                                   jint width, jint height, jint quality,
                                                   jint subsampling) {
  jbyteArray result = NULL;
  jpeg_error_ctx ctx;
  scratch s;
  jint *pixels;
  unsigned char *rgb = NULL;
  (void)thiz;
  if (width <= 0 || height <= 0) return NULL;
  memset(&s, 0, sizeof s);
  pixels = (*env)->GetIntArrayElements(env, argb, NULL);
  if (pixels == NULL) return NULL;
  jpeg_init_error(&ctx);

  if (setjmp(ctx.jump) == 0) {
    rgb = (unsigned char *)malloc((size_t)width * (size_t)height * 3);
    if (rgb != NULL) {
      for (int y = 0; y < height; y++) {
        unsigned char *dst = rgb + (size_t)y * (size_t)width * 3;
        const jint *src = pixels + (size_t)y * (size_t)width;
        for (int x = 0; x < width; x++) {
          const jint p = src[x];
          dst[x * 3] = (unsigned char)((p >> 16) & 0xFF);
          dst[x * 3 + 1] = (unsigned char)((p >> 8) & 0xFF);
          dst[x * 3 + 2] = (unsigned char)(p & 0xFF);
        }
      }
      if (encode_rgb(&ctx, &s, rgb, width, height, quality, subsampling)) {
        result = (*env)->NewByteArray(env, (jsize)s.encoded_size);
        if (result != NULL) {
          (*env)->SetByteArrayRegion(env, result, 0, (jsize)s.encoded_size,
                                     (const jbyte *)s.encoded);
        }
      }
    }
  }

  free(rgb);
  scratch_free(&s);
  (*env)->ReleaseIntArrayElements(env, argb, pixels, JNI_ABORT);
  return result;
}

/*
 * Decode for display, landing on an exact long edge.
 *
 * A libjpeg N/8 decode alone would quantise the preview size - a cap of 1400 on a
 * 4032 px photo becomes 1008, which is a visible loss on a screen whose whole job
 * is showing fine glitch detail. So the scaled decode is only used to get close,
 * and the Lanczos pass finishes the job at exactly maxDim. Small images skip the
 * scaling entirely and go through the full decode, which is the same pixels the
 * reference pipeline works on.
 */
JNIEXPORT jintArray JNICALL
Java_ca_justinmo_glitch_jpeg_JpegNative_decodePreviewArgb(JNIEnv *env, jobject thiz,
                                                          jbyteArray jpeg_bytes, jint max_dim,
                                                          jintArray out_size) {
  jintArray result = NULL;
  jpeg_error_ctx ctx;
  scratch s;
  jsize len = (*env)->GetArrayLength(env, jpeg_bytes);
  jbyte *bytes;
  (void)thiz;
  memset(&s, 0, sizeof s);
  bytes = (*env)->GetByteArrayElements(env, jpeg_bytes, NULL);
  if (bytes == NULL) return NULL;
  jpeg_init_error(&ctx);

  if (setjmp(ctx.jump) == 0) {
    int stored_w = 0, stored_h = 0;
    int scale = 8;
    if (probe_size(&ctx, (const unsigned char *)bytes, (size_t)len, &stored_w, &stored_h) &&
        max_dim > 0) {
      const long longest = stored_w > stored_h ? stored_w : stored_h;
      const double pixels = (double)stored_w * (double)stored_h;
      if (pixels > 12000000.0) {
        /* Big enough that a full decode is not worth the memory: land just above
         * the cap with a reduced-size IDCT, then resample the rest of the way. */
        while (scale > 1 && longest * (scale - 1) / 8 >= (long)max_dim) scale--;
      }
    }
    if (decode_rgb(&ctx, &s, (const unsigned char *)bytes, (size_t)len, scale)) {
      const unsigned char *pixels = s.rgb;
      int w = s.width;
      int h = s.height;
      if (max_dim > 0 && (w > max_dim || h > max_dim)) {
        const int tw = w >= h ? max_dim : (int)((double)w * (double)max_dim / (double)h + 0.5);
        const int th = w >= h ? (int)((double)h * (double)max_dim / (double)w + 0.5) : max_dim;
        if (resize_lanczos(&s, pixels, w, h, tw, th)) {
          pixels = s.resized;
          w = tw;
          h = th;
        }
      }
      {
        jintArray argb = (*env)->NewIntArray(env, (jsize)((size_t)w * (size_t)h));
        if (argb != NULL) {
          jint *row_ints = (jint *)malloc(sizeof(jint) * (size_t)w);
          if (row_ints != NULL) {
            for (int y = 0; y < h; y++) {
              const unsigned char *src = pixels + (size_t)y * (size_t)w * 3;
              for (int x = 0; x < w; x++) {
                row_ints[x] = (jint)(0xFF000000u | ((unsigned int)src[x * 3] << 16) |
                                     ((unsigned int)src[x * 3 + 1] << 8) |
                                     (unsigned int)src[x * 3 + 2]);
              }
              (*env)->SetIntArrayRegion(env, argb, (jsize)((size_t)y * (size_t)w), (jsize)w,
                                        row_ints);
            }
            free(row_ints);
            if (out_size != NULL && (*env)->GetArrayLength(env, out_size) >= 2) {
              jint size[2] = {(jint)w, (jint)h};
              (*env)->SetIntArrayRegion(env, out_size, 0, 2, size);
            }
            result = argb;
          }
        }
      }
    }
  }

  scratch_free(&s);
  (*env)->ReleaseByteArrayElements(env, jpeg_bytes, bytes, JNI_ABORT);
  return result;
}

/*
 * Decode, optionally crop, Lanczos-resize, and hand back pixels.
 *
 * The pixel form of [resizeJpeg], for two callers: the parity tests, which can
 * only check the resampler against Pillow's if the resampled pixels are visible
 * rather than only the JPEG they went into; and any screen that wants the scaled
 * picture without writing a file first.
 */
JNIEXPORT jintArray JNICALL
Java_ca_justinmo_glitch_jpeg_JpegNative_scaleArgb(JNIEnv *env, jobject thiz,
                                                  jbyteArray jpeg_bytes, jint target_width,
                                                  jfloat crop_aspect, jintArray out_size) {
  jintArray result = NULL;
  jpeg_error_ctx ctx;
  scratch s;
  jsize len = (*env)->GetArrayLength(env, jpeg_bytes);
  jbyte *bytes;
  (void)thiz;
  memset(&s, 0, sizeof s);
  bytes = (*env)->GetByteArrayElements(env, jpeg_bytes, NULL);
  if (bytes == NULL) return NULL;
  jpeg_init_error(&ctx);

  if (setjmp(ctx.jump) == 0) {
    if (decode_rgb(&ctx, &s, (const unsigned char *)bytes, (size_t)len, 8)) {
      const unsigned char *pixels = s.rgb;
      int w = s.width;
      int h = s.height;

      if (crop_aspect > 0.0f) {
        int cw = s.width;
        int ch = s.height;
        const double current = (double)s.width / (double)s.height;
        if (current > (double)crop_aspect) {
          cw = (int)((double)s.height * (double)crop_aspect + 0.5);
        } else {
          ch = (int)((double)s.width / (double)crop_aspect + 0.5);
        }
        if (cw < 1) cw = 1;
        if (ch < 1) ch = 1;
        if (cw < s.width || ch < s.height) {
          const int left = (s.width - cw) / 2;
          const int top = (s.height - ch) / 2;
          s.cropped = (unsigned char *)malloc((size_t)cw * (size_t)ch * 3);
          if (s.cropped == NULL) goto done;
          for (int y = 0; y < ch; y++) {
            memcpy(s.cropped + (size_t)y * (size_t)cw * 3,
                   s.rgb + ((size_t)(top + y) * (size_t)s.width + (size_t)left) * 3,
                   (size_t)cw * 3);
          }
          pixels = s.cropped;
          w = cw;
          h = ch;
        }
      }

      if (target_width > 0 && target_width < w) {
        const int th = (int)((double)h * (double)target_width / (double)w + 0.5);
        if (resize_lanczos(&s, pixels, w, h, target_width, th)) {
          pixels = s.resized;
          w = target_width;
          h = th;
        }
      }

      {
        jintArray argb = (*env)->NewIntArray(env, (jsize)((size_t)w * (size_t)h));
        if (argb != NULL) {
          jint *row_ints = (jint *)malloc(sizeof(jint) * (size_t)w);
          if (row_ints != NULL) {
            for (int y = 0; y < h; y++) {
              const unsigned char *src = pixels + (size_t)y * (size_t)w * 3;
              for (int x = 0; x < w; x++) {
                row_ints[x] = (jint)(0xFF000000u | ((unsigned int)src[x * 3] << 16) |
                                     ((unsigned int)src[x * 3 + 1] << 8) |
                                     (unsigned int)src[x * 3 + 2]);
              }
              (*env)->SetIntArrayRegion(env, argb, (jsize)((size_t)y * (size_t)w), (jsize)w,
                                        row_ints);
            }
            free(row_ints);
            if (out_size != NULL && (*env)->GetArrayLength(env, out_size) >= 2) {
              jint size[2] = {(jint)w, (jint)h};
              (*env)->SetIntArrayRegion(env, out_size, 0, 2, size);
            }
            result = argb;
          }
        }
      }
    }
  }

done:
  scratch_free(&s);
  (*env)->ReleaseByteArrayElements(env, jpeg_bytes, bytes, JNI_ABORT);
  return result;
}

/*
 * The reference pipeline in one call: decode, turn the picture the right way up,
 * optionally crop it, Lanczos-resize to a width, and re-encode.
 *
 * One call rather than three, because the intermediate pixels are the expensive
 * part - a 12 MP photo is 48 MB of RGB and there is no reason for it to cross
 * the JNI boundary or land in the Java heap.
 *
 * The crop is either a centred window of one aspect (crop_aspect > 0, the older
 * callers and the parity tests) or an exact rectangle given as proportions of the
 * upright picture (crop_width and crop_height > 0, which wins). Proportions rather
 * than pixels because the caller measures its window on the *preview* decode, which
 * is not the size of the file, and both decodes have the same shape. A target width
 * at or above the source leaves the size alone.
 *
 * `orientation` is the EXIF orientation of the input (1 = normal, the values
 * `ExifInterface.ORIENTATION_*` uses). It is applied here, before the crop, for
 * two reasons: the file this writes carries no EXIF, so a camera photo whose
 * stored grid is sideways would be handed on sideways with nothing left to say
 * otherwise; and the crop and the target width have to be measured on the
 * picture the user is looking at, not on the grid the sensor wrote. A caller
 * that has no tag to report passes 1 and gets the pipeline unchanged.
 */
JNIEXPORT jbyteArray JNICALL
Java_ca_justinmo_glitch_jpeg_JpegNative_resizeJpeg(JNIEnv *env, jobject thiz,
                                                   jbyteArray jpeg_bytes, jint target_width,
                                                   jint quality, jint subsampling,
                                                   jfloat crop_aspect, jfloat crop_left,
                                                   jfloat crop_top, jfloat crop_width,
                                                   jfloat crop_height, jint orientation) {
  jbyteArray result = NULL;
  jpeg_error_ctx ctx;
  scratch s;
  jsize len = (*env)->GetArrayLength(env, jpeg_bytes);
  jbyte *bytes;
  (void)thiz;
  memset(&s, 0, sizeof s);
  bytes = (*env)->GetByteArrayElements(env, jpeg_bytes, NULL);
  if (bytes == NULL) return NULL;
  jpeg_init_error(&ctx);

  if (setjmp(ctx.jump) == 0) {
    if (decode_rgb(&ctx, &s, (const unsigned char *)bytes, (size_t)len, 8)) {
      const unsigned char *source = s.rgb;
      int source_w = s.width;
      int source_h = s.height;

      /* Upright before anything is measured on it: see the comment above. */
      if (orientation > 1 && orientation <= 8) {
        if (!orient_upright(&s, (int)orientation)) goto done;
        /* The stored grid is not read again, and a 12 MP photo is 36 MB of it. */
        free(s.rgb);
        s.rgb = NULL;
        source = s.upright;
        source_w = s.upright_width;
        source_h = s.upright_height;
      }

      {
        int left = 0;
        int top = 0;
        int cw = source_w;
        int ch = source_h;
        if (crop_width > 0.0f && crop_height > 0.0f) {
          /* The exact rectangle: proportions of the upright picture, clamped in. */
          double l = (double)crop_left;
          double t = (double)crop_top;
          double w = (double)crop_width;
          double h = (double)crop_height;
          if (l < 0.0) l = 0.0;
          if (l > 1.0) l = 1.0;
          if (t < 0.0) t = 0.0;
          if (t > 1.0) t = 1.0;
          if (w < 0.0) w = 0.0;
          if (w > 1.0) w = 1.0;
          if (h < 0.0) h = 0.0;
          if (h > 1.0) h = 1.0;
          left = (int)(l * (double)source_w + 0.5);
          top = (int)(t * (double)source_h + 0.5);
          cw = (int)(w * (double)source_w + 0.5);
          ch = (int)(h * (double)source_h + 0.5);
          if (left >= source_w) left = source_w - 1;
          if (top >= source_h) top = source_h - 1;
          if (cw > source_w - left) cw = source_w - left;
          if (ch > source_h - top) ch = source_h - top;
          if (cw < 1) cw = 1;
          if (ch < 1) ch = 1;
        } else if (crop_aspect > 0.0f) {
          const double current = (double)source_w / (double)source_h;
          if (current > (double)crop_aspect) {
            cw = (int)((double)source_h * (double)crop_aspect + 0.5);
          } else {
            ch = (int)((double)source_w / (double)crop_aspect + 0.5);
          }
          if (cw < 1) cw = 1;
          if (ch < 1) ch = 1;
          left = (source_w - cw) / 2;
          top = (source_h - ch) / 2;
        }
        if (cw < source_w || ch < source_h) {
          s.cropped = (unsigned char *)malloc((size_t)cw * (size_t)ch * 3);
          if (s.cropped == NULL) goto done;
          for (int y = 0; y < ch; y++) {
            memcpy(s.cropped + (size_t)y * (size_t)cw * 3,
                   source + ((size_t)(top + y) * (size_t)source_w + (size_t)left) * 3,
                   (size_t)cw * 3);
          }
          source = s.cropped;
          source_w = cw;
          source_h = ch;
        }
      }

      {
        const int tw = target_width > 0 && target_width < source_w ? target_width : source_w;
        const int th = (int)((double)source_h * (double)tw / (double)source_w + 0.5);
        int encode_w = source_w;
        int encode_h = source_h;
        const unsigned char *encode_src = source;
        if (tw != source_w || th != source_h) {
          if (resize_lanczos(&s, source, source_w, source_h, tw, th)) {
            encode_src = s.resized;
            encode_w = tw;
            encode_h = th;
          }
        }
        if (encode_rgb(&ctx, &s, encode_src, encode_w, encode_h, quality, subsampling)) {
          result = (*env)->NewByteArray(env, (jsize)s.encoded_size);
          if (result != NULL) {
            (*env)->SetByteArrayRegion(env, result, 0, (jsize)s.encoded_size,
                                       (const jbyte *)s.encoded);
          }
        }
      }
    }
  }

done:
  scratch_free(&s);
  (*env)->ReleaseByteArrayElements(env, jpeg_bytes, bytes, JNI_ABORT);
  return result;
}
