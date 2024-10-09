#include "include/android/SkAnimatedImage.h"
#include "include/codec/SkAndroidCodec.h"
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
#include "include/private/SkShadowFlags.h"
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

#ifdef CK_ENABLE_WEBGPU
#include <emscripten/html5_webgpu.h>
#include <webgpu/webgpu.h>
#include <webgpu/webgpu_cpp.h>
#endif // CK_ENABLE_WEBGPU

#ifndef CK_NO_FONTS
#include "include/core/SkFont.h"
#include "include/core/SkFontMetrics.h"
#include "include/core/SkFontMgr.h"
#include "include/core/SkFontTypes.h"
#ifdef CK_INCLUDE_PARAGRAPH
#include "modules/skparagraph/include/Paragraph.h"
#endif // CK_INCLUDE_PARAGRAPH
#endif // CK_NO_FONTS

#ifdef CK_INCLUDE_PATHOPS
#include "include/pathops/SkPathOps.h"
#endif

#if defined(CK_INCLUDE_RUNTIME_EFFECT) && defined(SKSL_ENABLE_TRACING)
#include "include/sksl/SkSLDebugTrace.h"
#endif

#ifndef CK_NO_FONTS
#include "include/ports/SkFontMgr_data.h"
#endif

// Global data needed to keep everything in place.
sk_sp<GrDirectContext> context = nullptr;
sk_sp<SkSurface> surface = nullptr;
SkCanvas *canvas = nullptr;

struct PenpotRect {
    float x, y, width, height;
};

struct PenpotColor {
    float r, g, b, a;
};

struct PenpotObject {
    PenpotRect selRect;
};

std::vector<PenpotObject> objects(0);

// Initializes all the structures and elements needed to start rendering things.
void InitCanvas(int width, int height)
{
    emscripten_log(EM_LOG_CONSOLE, "Initializing canvas %d %d", width, height);

    // We assume that any calls we make to GL for the remainder of this function will go to the
    // desired WebGL Context.
    // setup interface.
    auto interface = GrGLMakeNativeInterface();
    // setup context.
    context = GrDirectContext::MakeGL(interface);

    emscripten_log(EM_LOG_CONSOLE, "GL context initialized");

    GrGLint sampleCnt = 0;
    GrGLint stencil = 16;

    // WebGL should already be clearing the color and stencil buffers, but do it again here to
    // ensure Skia receives them in the expected state.
    emscripten_glBindFramebuffer(GL_FRAMEBUFFER, 0);
    emscripten_glClearColor(0, 0, 0, 0);
    emscripten_glClearStencil(0);
    emscripten_glClear(GL_COLOR_BUFFER_BIT | GL_STENCIL_BUFFER_BIT);
    context->resetContext(kRenderTarget_GrGLBackendState | kMisc_GrGLBackendState);

    // The on-screen canvas is FBO 0. Wrap it in a Skia render target so Skia can render to it.
    GrGLFramebufferInfo info;
    info.fFBOID = 0;

    // Create the colorspace needed to represent graphics.
    sk_sp<SkColorSpace> colorSpace = SkColorSpace::MakeSRGB();

    info.fFormat = GR_GL_RGBA8; // kRGBA_8888_SkColorType;
    auto target = GrBackendRenderTargets::MakeGL(
        width,
        height,
        sampleCnt,
        stencil,
        info
    );

    emscripten_log(EM_LOG_CONSOLE, "Creating new surface");
    sk_sp<SkSurface> new_surface(
        SkSurfaces::WrapBackendRenderTarget(
            context.get(),
            target,
            kBottomLeft_GrSurfaceOrigin,
            kRGBA_8888_SkColorType,
            colorSpace,
            nullptr));

    surface = new_surface;
    canvas = surface->getCanvas();
    emscripten_log(EM_LOG_CONSOLE, "Everything's ready!");
}

void DrawCanvas(float x, float y, float zoom)
{
    canvas->clear(SK_ColorTRANSPARENT);
    canvas->save();
    canvas->scale(zoom, zoom);
    canvas->translate(-x, -y);
    emscripten_log(EM_LOG_CONSOLE, "Clearing canvas");
    for (auto object : objects) {
        emscripten_log(EM_LOG_CONSOLE, "Drawing object");

        SkPaint paint;
        paint.setARGB(255, 255, 0, 0);
        paint.setStyle(SkPaint::Style::kFill_Style);

        SkRect rect = SkRect::MakeXYWH(object.selRect.x, object.selRect.y, object.selRect.width, object.selRect.height);
        canvas->drawRect(rect, paint);
    }
    canvas->restore();

    emscripten_log(EM_LOG_CONSOLE, "Flushing and submitting");
    skgpu::ganesh::FlushAndSubmit(surface);
}

void SetObjects(int num_objects) {
    emscripten_log(EM_LOG_CONSOLE, "Resizing objects vector capacity %d", num_objects);
    objects.resize(num_objects);
}

void SetObject(int index, float x, float y, float width, float height) {
    emscripten_log(EM_LOG_CONSOLE, "Setting object at %d %f %f %f %f", index, x, y, width, height);
    objects[index].selRect.x = x;
    objects[index].selRect.y = y;
    objects[index].selRect.width = width;
    objects[index].selRect.height = height;
}

EMSCRIPTEN_BINDINGS(Renderer)
{
    function("_InitCanvas", InitCanvas);
    function("_DrawCanvas", DrawCanvas);
    function("_SetObjects", SetObjects);
    function("_SetObject", SetObject);
}
