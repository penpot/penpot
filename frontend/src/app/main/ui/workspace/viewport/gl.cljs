(ns app.main.ui.workspace.viewport.gl
   (:require-macros [app.main.style :as stl])
   (:require-macros [app.util.gl.macros :refer [slurp]])
   (:require
    [app.common.math :as math]
    [app.util.gl :as gl]
    [cuerdas.core :as str]
    [rumext.v2 :as mf]))

 (def CANVAS_CONTEXT_ID "webgl2")

 (def default-vertex-shader (slurp "src/app/util/gl/shaders/default.v.glsl"))
 (def default-fragment-shader (slurp "src/app/util/gl/shaders/default.f.glsl"))

 #_(def shaders (js/Map.))
 (def programs (js/Map.))
 #_(def textures (js/Map.))
 #_(def framebuffers (js/Map.))

 (defn parse-color-hex
   [color opacity]
   (let [r (str/slice color 1 3)
         g (str/slice color 3 5)
         b (str/slice color 5 7)]
     #js [(/ (js/parseInt r 16) 255.0)
          (/ (js/parseInt g 16) 255.0)
          (/ (js/parseInt b 16) 255.0)
          opacity]))

 (defn parse-color
   [color opacity]
   (cond
     (str/starts-with? color "#")
     (parse-color-hex color opacity)

     :else
     #js [0.0 0.0 0.0 1.0]))

 (defn resize-canvas-to
   [canvas width height]
   (let [resized-width (not= (.-width canvas) width)
         resized-height (not= (.-height canvas) height)
         resized (or resized-width resized-height)]
     (when resized-width
       (set! (.-width canvas) width))
     (when resized-height
       (set! (.-height canvas) height))
     resized))

 (defn resize-canvas
   [canvas]
   (let [width  (math/floor (.-clientWidth canvas))
         height (math/floor (.-clientHeight canvas))]
     (resize-canvas-to canvas width height)))

 (defn prepare-gl
   [gl]
   (let [default-program (gl/create-program-from-sources gl default-vertex-shader default-fragment-shader)]
     (.set programs "default" default-program)))

(defn render-gl
  [gl objects vbox]
  (.clearColor gl 1.0 0.0 1.0 0)
  (.clear gl (.-COLOR_BUFFER_BIT gl))

  (.viewport gl 0 0 (.-width (.-canvas gl)) (.-height (.-canvas gl)))

  (.useProgram gl (.get programs "default"))
  (.uniform4f gl (.getUniformLocation gl (.get programs "default") "u_vbox") (:x vbox) (:y vbox) (:width vbox) (:height vbox))

  (.enable gl (.-BLEND gl))
  (.blendFunc gl (.-SRC_ALPHA gl) (.-ONE_MINUS_SRC_ALPHA gl))

  (doseq [[_ object] objects]
    (let [selrect    (:selrect object)
          x (:x selrect)
          y (:y selrect)
          width (:width selrect)
          height (:height selrect)]
      (doseq [fill (reverse (:fills object))]
        (do
          (.uniform4fv gl (.getUniformLocation gl (.get programs "default") "u_color") (parse-color (:fill-color fill) (:fill-opacity fill)))
          (.uniform2f gl (.getUniformLocation gl (.get programs "default") "u_size") width height)
          (.uniform2f gl (.getUniformLocation gl (.get programs "default") "u_position") x y)
          (.drawArrays gl (.-TRIANGLE_STRIP gl) 0 4))))))

(mf/defc canvas
  "A canvas element with a WebGL context."
  {::mf/wrap-props false}
  [props]
  (let [objects    (unchecked-get props "objects")
        vbox       (unchecked-get props "vbox")
        canvas-ref (mf/use-ref nil)
        gl-ref     (mf/use-ref nil)]

    (mf/with-effect [objects vbox]
      (let [gl (mf/ref-val gl-ref)]
        (when (some? gl)
          (render-gl gl objects vbox))))

    (mf/with-effect [canvas-ref]
      (let [canvas (mf/ref-val canvas-ref)]
        (when (some? canvas)
          (let [gl (.getContext canvas CANVAS_CONTEXT_ID)]
            (mf/set-ref-val! gl-ref gl)
            (resize-canvas canvas)
            (prepare-gl gl)
            (render-gl gl objects vbox)))))

    [:canvas {:class (stl/css :canvas)
              :ref canvas-ref}]))

;; TODO 
;; - blend modes
;; - strokes