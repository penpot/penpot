#include "include/private/base/SkMalloc.h"
#include "include/android/SkAnimatedImage.h"
#include "include/codec/SkAndroidCodec.h"
#include "include/codec/SkCodec.h"
#include "include/codec/SkEncodedImageFormat.h"
#include "include/core/SkBBHFactory.h"
#include "include/core/SkBlendMode.h"
#include "include/core/SkBlender.h"
#include "include/core/SkBlurTypes.h"
#include "include/core/SkCanvas.h"
#include "include/core/SkColor.h"
#include "include/core/SkColorFilter.h"
#include "include/core/SkColorSpace.h"
#include "include/core/SkData.h"
#include "include/core/SkImage.h"
#include "include/core/SkImageFilter.h"
#include "include/core/SkImageGenerator.h"
#include "include/core/SkImageInfo.h"
#include "include/core/SkM44.h"
#include "include/core/SkMaskFilter.h"
#include "include/core/SkPaint.h"
#include "include/core/SkPath.h"
#include "include/core/SkPathEffect.h"
#include "include/core/SkPathMeasure.h"
#include "include/core/SkPathUtils.h"
#include "include/core/SkPicture.h"
#include "include/core/SkPictureRecorder.h"
#include "include/core/SkPoint3.h"
#include "include/core/SkRRect.h"
#include "include/core/SkSamplingOptions.h"
#include "include/core/SkScalar.h"
#include "include/core/SkSerialProcs.h"
#include "include/core/SkShader.h"
#include "include/core/SkSpan.h"
#include "include/core/SkStream.h"
#include "include/core/SkString.h"
#include "include/core/SkStrokeRec.h"
#include "include/core/SkSurface.h"
#include "include/core/SkTextBlob.h"
#include "include/core/SkTypeface.h"
#include "include/core/SkTypes.h"
#include "include/core/SkVertices.h"
#include "include/effects/Sk1DPathEffect.h"
#include "include/effects/Sk2DPathEffect.h"
#include "include/effects/SkCornerPathEffect.h"
#include "include/effects/SkDashPathEffect.h"
#include "include/effects/SkDiscretePathEffect.h"
#include "include/effects/SkGradientShader.h"
#include "include/effects/SkImageFilters.h"
#include "include/effects/SkLumaColorFilter.h"
#include "include/effects/SkPerlinNoiseShader.h"
#include "include/effects/SkRuntimeEffect.h"
#include "include/effects/SkTrimPathEffect.h"
#include "include/encode/SkJpegEncoder.h"
#include "include/encode/SkPngEncoder.h"
#include "include/encode/SkWebpEncoder.h"
#include "include/private/base/SkOnce.h"
#include "include/utils/SkParsePath.h"
#include "include/utils/SkShadowUtils.h"
#include "src/core/SkPathPriv.h"
#include "src/core/SkResourceCache.h"
#include "src/image/SkImage_Base.h"
#include "src/sksl/SkSLCompiler.h"

#include "modules/canvaskit/WasmCommon.h"
#include <emscripten.h>
#include <emscripten/bind.h>
#include <emscripten/html5.h>

#if defined(CK_ENABLE_WEBGL) || defined(CK_ENABLE_WEBGPU)
#define ENABLE_GPU
#endif

#ifdef ENABLE_GPU
#include "include/gpu/GpuTypes.h"
#include "include/gpu/GrDirectContext.h"
#include "include/gpu/ganesh/GrExternalTextureGenerator.h"
#include "include/gpu/ganesh/SkImageGanesh.h"
#include "include/gpu/ganesh/SkSurfaceGanesh.h"
#include "src/gpu/ganesh/GrCaps.h"
#endif // ENABLE_GPU

#ifdef CK_ENABLE_WEBGL
#include "include/gpu/GrBackendSurface.h"
#include "include/gpu/GrTypes.h"
#include "include/gpu/ganesh/gl/GrGLBackendSurface.h"
#include "include/gpu/gl/GrGLInterface.h"
#include "include/gpu/gl/GrGLTypes.h"
#include "src/gpu/RefCntedCallback.h"
#include "src/gpu/ganesh/GrProxyProvider.h"
#include "src/gpu/ganesh/GrRecordingContextPriv.h"
#include "src/gpu/ganesh/gl/GrGLDefines.h"

#include <webgl/webgl1.h>
#endif // CK_ENABLE_WEBGL

#ifdef CK_INCLUDE_PATHOPS
#include "include/pathops/SkPathOps.h"
#endif

// Necessary to prevent C++ name mangling.
extern "C" {
  EMSCRIPTEN_KEEPALIVE int add(int a, int b) {
    return a + b;
  }
}

struct OptionalMatrix : SkMatrix {
    OptionalMatrix(WASMPointerF32 mPtr) {
        if (mPtr) {
            const SkScalar* nineMatrixValues = reinterpret_cast<const SkScalar*>(mPtr);
            this->set9(nineMatrixValues);
        }
    }
};

SkColor4f ptrToSkColor4f(WASMPointerF32 cPtr) {
    float* fourFloats = reinterpret_cast<float*>(cPtr);
    SkColor4f color;
    memcpy(&color, fourFloats, 4 * sizeof(float));
    return color;
}

SkRRect ptrToSkRRect(WASMPointerF32 fPtr) {
    // In order, these floats should be 4 floats for the rectangle
    // (left, top, right, bottom) and then 8 floats for the radii
    // (upper left, upper right, lower right, lower left).
    const SkScalar* twelveFloats = reinterpret_cast<const SkScalar*>(fPtr);
    const SkRect rect = reinterpret_cast<const SkRect*>(twelveFloats)[0];
    const SkVector* radiiValues = reinterpret_cast<const SkVector*>(twelveFloats + 4);

    SkRRect rr;
    rr.setRectRadii(rect, radiiValues);
    return rr;
}

// Surface creation structs and helpers
struct SimpleImageInfo {
    int width;
    int height;
    SkColorType colorType;
    SkAlphaType alphaType;
    sk_sp<SkColorSpace> colorSpace;
};

SkImageInfo toSkImageInfo(const SimpleImageInfo& sii) {
    return SkImageInfo::Make(sii.width, sii.height, sii.colorType, sii.alphaType,
                             sii.colorSpace ? sii.colorSpace : SkColorSpace::MakeSRGB());
}

#ifdef CK_ENABLE_WEBGL

// Set the pixel format based on the colortype.
// These degrees of freedom are removed from canvaskit only to keep the interface simpler.
struct ColorSettings {
    ColorSettings(sk_sp<SkColorSpace> colorSpace) {
        if (colorSpace == nullptr || colorSpace->isSRGB()) {
            colorType = kRGBA_8888_SkColorType;
            pixFormat = GR_GL_RGBA8;
        } else {
            colorType = kRGBA_F16_SkColorType;
            pixFormat = GR_GL_RGBA16F;
        }
    }
    SkColorType colorType;
    GrGLenum pixFormat;
};

sk_sp<GrDirectContext> MakeGrContext()
{
    // We assume that any calls we make to GL for the remainder of this function will go to the
    // desired WebGL Context.
    // setup interface.
    auto interface = GrGLMakeNativeInterface();
    // setup context
    return GrDirectContext::MakeGL(interface);
}

sk_sp<SkSurface> MakeOnScreenGLSurface(sk_sp<GrDirectContext> dContext, int width, int height,
                                       sk_sp<SkColorSpace> colorSpace, int sampleCnt, int stencil) {
    // WebGL should already be clearing the color and stencil buffers, but do it again here to
    // ensure Skia receives them in the expected state.
    emscripten_glBindFramebuffer(GL_FRAMEBUFFER, 0);
    emscripten_glClearColor(0, 0, 0, 0);
    emscripten_glClearStencil(0);
    emscripten_glClear(GL_COLOR_BUFFER_BIT | GL_STENCIL_BUFFER_BIT);
    dContext->resetContext(kRenderTarget_GrGLBackendState | kMisc_GrGLBackendState);

    // The on-screen canvas is FBO 0. Wrap it in a Skia render target so Skia can render to it.
    GrGLFramebufferInfo info;
    info.fFBOID = 0;

    if (!colorSpace) {
        colorSpace = SkColorSpace::MakeSRGB();
    }

    const auto colorSettings = ColorSettings(colorSpace);
    info.fFormat = colorSettings.pixFormat;
    auto target = GrBackendRenderTargets::MakeGL(width, height, sampleCnt, stencil, info);
    sk_sp<SkSurface> surface(SkSurfaces::WrapBackendRenderTarget(dContext.get(),
                                                                 target,
                                                                 kBottomLeft_GrSurfaceOrigin,
                                                                 colorSettings.colorType,
                                                                 colorSpace,
                                                                 nullptr));
    return surface;
}

sk_sp<SkSurface> MakeOnScreenGLSurface(sk_sp<GrDirectContext> dContext, int width, int height,
                                       sk_sp<SkColorSpace> colorSpace) {
    GrGLint sampleCnt;
    emscripten_glGetIntegerv(GL_SAMPLES, &sampleCnt);

    GrGLint stencil;
    emscripten_glGetIntegerv(GL_STENCIL_BITS, &stencil);

    return MakeOnScreenGLSurface(dContext, width, height, colorSpace, sampleCnt, stencil);
}

sk_sp<SkSurface> MakeRenderTarget(sk_sp<GrDirectContext> dContext, int width, int height) {
    SkImageInfo info = SkImageInfo::MakeN32(
            width, height, SkAlphaType::kPremul_SkAlphaType, SkColorSpace::MakeSRGB());

    sk_sp<SkSurface> surface(SkSurfaces::RenderTarget(dContext.get(),
                                                      skgpu::Budgeted::kYes,
                                                      info,
                                                      0,
                                                      kBottomLeft_GrSurfaceOrigin,
                                                      nullptr,
                                                      true));
    return surface;
}

sk_sp<SkSurface> MakeRenderTarget(sk_sp<GrDirectContext> dContext, SimpleImageInfo sii) {
    sk_sp<SkSurface> surface(SkSurfaces::RenderTarget(dContext.get(),
                                                      skgpu::Budgeted::kYes,
                                                      toSkImageInfo(sii),
                                                      0,
                                                      kBottomLeft_GrSurfaceOrigin,
                                                      nullptr,
                                                      true));
    return surface;
}
#endif // CK_ENABLE_WEBGL

EMSCRIPTEN_BINDINGS(Renderer) {
#ifdef ENABLE_GPU
    constant("gpu", true);
    function("_MakeGrContext", &MakeGrContext);
#endif // ENABLE_GPU

#ifdef CK_ENABLE_WEBGL
    constant("webgl", true);
    function("_MakeOnScreenGLSurface", select_overload<sk_sp<SkSurface>(sk_sp<GrDirectContext>, int, int, sk_sp<SkColorSpace>)>(&MakeOnScreenGLSurface));
    function("_MakeOnScreenGLSurface", select_overload<sk_sp<SkSurface>(sk_sp<GrDirectContext>, int, int, sk_sp<SkColorSpace>, int, int)>(&MakeOnScreenGLSurface));
    function("_MakeRenderTargetWH", select_overload<sk_sp<SkSurface>(sk_sp<GrDirectContext>, int, int)>(&MakeRenderTarget));
    function("_MakeRenderTargetII", select_overload<sk_sp<SkSurface>(sk_sp<GrDirectContext>, SimpleImageInfo)>(&MakeRenderTarget));
#endif // CK_ENABLE_WEBGL

}
