(ns app.util.gl)

;;
;; Shaders
;;
(defn create-shader
  "Creates a shader of the given type with the given source"
  [gl type source]
  (let [shader (.createShader gl type)]
    (.shaderSource gl shader source)
    (.compileShader gl shader)
    (when-not (.getShaderParameter gl shader (.COMPILE_STATUS gl))
      (throw (js/Error. (.getShaderInfoLog gl shader))))))

(defn create-vertex-shader
  "Creates a vertex shader with the given source"
  [gl source]
  (create-shader gl (.VERTEX_SHADER gl) source))

(defn create-fragment-shader
  "Creates a fragment shader with the given source"
  [gl source]
  (create-shader gl (.FRAGMENT_SHADER gl) source))

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
    (when-not (.getProgramParameter gl program (.LINK_STATUS gl))
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
          get-active (dm/get gl get-active-name)
          get-location (dm/get gl get-location-name)]
       (into {} (for [index (range count)]
                  (let [info         (.get-active gl program index)
                        name         (.-name info)
                        location     (.get-location gl program name)]
                    [name #js {:name name :info info :location location}]))))))

(def get-program-uniforms (get-program-active-factory (.ACTIVE_UNIFORMS js/WebGLRenderingContext) "getActiveUniform" "getUniformLocation"))
(def get-program-attributes (get-program-active-factory (.ACTIVE_ATTRIBUTES js/WebGLRenderingContext) "getActiveAttrib" "getAttribLocation"))

(defn get-program-actives
  "Returns a map of uniform names to uniform locations for the given program"
  [gl program]
  (let [uniforms (get-program-uniforms gl program)
        attributes (get-program-attributes gl program)]
    #js { :uniforms uniforms :attributes attributes }))

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
  [gl]
  (let [framebuffer (.createFramebuffer gl)]
    (.bindFramebuffer gl framebuffer)
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

