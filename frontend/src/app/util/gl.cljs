(ns app.util.gl
  (:require [app.common.data.macros :as dm]))

;;
;; Shaders
;;
(defn get-shader-type
  [gl type]
  (cond
    (= type (.-VERTEX_SHADER gl)) "vertex shader"
    (= type (.-FRAGMENT_SHADER gl)) "fragment shader"
    :else "unknown shader type"))

(defn create-shader
  "Creates a shader of the given type with the given source"
  [gl type source]
  (let [shader (.createShader gl type)]
    (.shaderSource gl shader source)
    (.compileShader gl shader)
    (when-not (.getShaderParameter gl shader (.-COMPILE_STATUS gl))
      (throw (js/Error. (dm/str (get-shader-type gl type) " " (.getShaderInfoLog gl shader)))))
    shader))

(defn create-vertex-shader
  "Creates a vertex shader with the given source"
  [gl source]
  (create-shader gl (.-VERTEX_SHADER gl) source))

(defn create-fragment-shader
  "Creates a fragment shader with the given source"
  [gl source]
  (create-shader gl (.-FRAGMENT_SHADER gl) source))

;;
;; Programs
;;
(defn create-program
  "Creates a program with the given vertex and fragment shaders"
  [gl vertex-shader fragment-shader]
  (let [program (.createProgram gl)]
    (.attachShader gl program vertex-shader)
    (.attachShader gl program fragment-shader)
    (.linkProgram gl program)
    (when-not (.getProgramParameter gl program (.-LINK_STATUS gl))
      (throw (js/Error. (.getProgramInfoLog gl program))))
    program))

(defn create-program-from-sources
  "Creates a program with the given vertex and fragment shader sources"
  [gl vertex-source fragment-source]
  (let [vertex-shader (create-vertex-shader gl vertex-source)
        fragment-shader (create-fragment-shader gl fragment-source)]
    (create-program gl vertex-shader fragment-shader)))

(defn get-program-active-factory
  "Returns a map of active uniform and attribute names to their locations"
  [parameter get-active-name get-location-name]
  (fn [gl program]
    (let [count (.getProgramParameter gl program parameter)
          get-active (unchecked-get gl get-active-name)
          get-location (unchecked-get gl get-location-name)
          actives #js {}]
      (doseq [index (range 0 count)]
        (let [info         (.apply get-active gl #js [program index])
              name         (.-name info)
              location     (.apply get-location gl #js [program name])]
          (.defineProperty js/Object actives name #js {:value #js {:name name :info info :location location} :enumerable true :writable false :configurable false})))
      actives)))

(def get-program-uniforms (get-program-active-factory (.-ACTIVE_UNIFORMS js/WebGLRenderingContext) "getActiveUniform" "getUniformLocation"))
(def get-program-attributes (get-program-active-factory (.-ACTIVE_ATTRIBUTES js/WebGLRenderingContext) "getActiveAttrib" "getAttribLocation"))

(defn get-program-actives
  "Returns a map of uniform names to uniform locations for the given program"
  [gl program]
  (let [uniforms (get-program-uniforms gl program)
        attributes (get-program-attributes gl program)]
    #js { :uniforms uniforms :attributes attributes }))

(defn get-program-uniform-location
  "Returns the location of the given uniform in the given program"
  [actives name]
  (let [uniforms (unchecked-get actives "uniforms")
        uniform (unchecked-get uniforms name)]
    (unchecked-get uniform "location")))

;;
;; Buffers
;;
(defn create-buffer-from-data
  [gl data target usage]
  (let [buffer (.createBuffer gl)]
    (.bindBuffer gl target buffer)
    (.bufferData gl target data usage)
    (.bindBuffer gl target nil)
    buffer))

;;
;; Framebuffers
;;
(defn create-framebuffer
  [gl attachments]
  (let [framebuffer (.createFramebuffer gl)]
    (.bindFramebuffer gl framebuffer)
    (doseq [[attachment attachment-info] attachments]
      (let [attachment-type (unchecked-get attachment-info "type")
            attachment-data (unchecked-get attachment-info "data")]
        (cond
          (= attachment-type "texture")
          (.framebufferTexture2D gl (.-FRAMEBUFFER gl) attachment (.-TEXTURE_2D gl) attachment-data 0)
          (= attachment-type "renderbuffer")
          (.framebufferRenderbuffer gl (.-FRAMEBUFFER gl) attachment (.-RENDERBUFFER gl) attachment-data)
          :else
          (throw (js/Error. (dm/str "Unknown attachment type: " attachment-type))))))
    (.bindFramebuffer gl nil)
    framebuffer))

;;
;; Renderbuffers
;;
(defn create-renderbuffer
  [gl]
  (let [renderbuffer (.createRenderbuffer gl)]
    (.bindRenderbuffer gl renderbuffer)
    (.bindRenderbuffer gl nil)
    renderbuffer))

