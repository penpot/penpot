;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.color
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.media :as cm]
   [app.common.schema :as sm]
   [app.common.schema.generators :as sg]
   [app.common.schema.openapi :as-alias oapi]
   [app.common.text :as txt]
   [app.common.time :as dt]
   [app.common.types.plugins :as ctpg]
   [clojure.set :as set]
   [cuerdas.core :as str]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SCHEMAS & TYPES
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private required-color-attrs
  "A set used for proper check if color should contain only one of the
  attrs listed in this set."
  #{:image :gradient :color})

(defn has-valid-color-attrs?
  "Check if color has correct color attrs"
  [color]
  (let [attrs (set (keys color))
        result (set/intersection attrs required-color-attrs)]
    (= 1 (count result))))

(def ^:private hex-color-rx
  #"^#(?:[0-9a-fA-F]{3}){1,2}$")

(def ^:private hex-color-generator
  (sg/fmap (fn [_]
             #?(:clj (format "#%06x" (rand-int 16rFFFFFF))
                :cljs
                (let [r (rand-int 255)
                      g (rand-int 255)
                      b (rand-int 255)]
                  (str "#"
                       (.. r (toString 16) (padStart 2 "0"))
                       (.. g (toString 16) (padStart 2 "0"))
                       (.. b (toString 16) (padStart 2 "0"))))))
           sg/int))

(defn hex-color-string?
  [o]
  (and (string? o) (some? (re-matches hex-color-rx o))))

(def schema:hex-color
  (sm/register!
   {:type ::hex-color
    :pred hex-color-string?
    :type-properties
    {:title "hex-color"
     :description "HEX Color String"
     :error/message "expected a valid HEX color"
     :error/code "errors.invalid-hex-color"
     :gen/gen hex-color-generator
     ::oapi/type "integer"
     ::oapi/format "int64"}}))

(def schema:plain-color
  [:map [:color schema:hex-color]])

(def schema:image
  [:map {:title "ImageColor" :closed true}
   [:width [::sm/int {:min 0 :gen/gen sg/int}]]
   [:height [::sm/int {:min 0 :gen/gen sg/int}]]
   [:mtype {:gen/gen (sg/elements cm/image-types)} ::sm/text]
   [:id ::sm/uuid]
   [:name {:optional true} ::sm/text]
   [:keep-aspect-ratio {:optional true} :boolean]])

(def image-attrs
  "A set of attrs that corresponds to image data type"
  (sm/keys schema:image))

(def schema:image-color
  [:map [:image schema:image]])

(def gradient-types
  #{:linear :radial})

(def schema:gradient
  [:map {:title "Gradient" :closed true}
   [:type [::sm/one-of gradient-types]]
   [:start-x ::sm/safe-number]
   [:start-y ::sm/safe-number]
   [:end-x ::sm/safe-number]
   [:end-y ::sm/safe-number]
   [:width ::sm/safe-number]
   [:stops
    [:vector {:min 1 :gen/max 2}
     [:map {:title "GradientStop"}
      [:color schema:hex-color]
      [:opacity {:optional true} [::sm/number {:min 0 :max 1}]]
      [:offset [::sm/number {:min 0 :max 1}]]]]]])

(def gradient-attrs
  "A set of attrs that corresponds to gradient data type"
  (sm/keys schema:gradient))

(def schema:gradient-color
  [:map [:gradient schema:gradient]])

(def schema:color-attrs
  [:map {:title "ColorAttrs" :closed true}
   [:opacity {:optional true} [::sm/number {:min 0 :max 1}]]
   [:ref-id {:optional true} ::sm/uuid]
   [:ref-file {:optional true} ::sm/uuid]])

;; This schema represent an "applied color"
(def schema:color
  [:and
   [:merge {:title "Color"}
    schema:color-attrs
    (sm/optional-keys schema:plain-color)
    (sm/optional-keys schema:gradient-color)
    (sm/optional-keys schema:image-color)]
   [:fn has-valid-color-attrs?]])

(def color-attrs
  (into required-color-attrs (sm/keys schema:color-attrs)))

(def schema:library-color-attrs
  [:map {:title "ColorAttrs" :closed true}
   [:id ::sm/uuid]
   [:name ::sm/text]
   [:path {:optional true} :string]
   [:opacity {:optional true} [::sm/number {:min 0 :max 1}]]
   [:modified-at {:optional true} ::sm/inst]
   [:plugin-data {:optional true} ::ctpg/plugin-data]])

(def schema:library-color
  "Used for in-transit representation of a color (per example when user
  clicks a color on assets sidebar, the color should be properly identified with
  the file-id where it belongs)"
  [:and
   [:merge
    schema:library-color-attrs
    (sm/optional-keys schema:plain-color)
    (sm/optional-keys schema:gradient-color)
    (sm/optional-keys schema:image-color)]
   [:fn has-valid-color-attrs?]])

(def library-color-attrs
  (into required-color-attrs (sm/keys schema:library-color-attrs)))

(def valid-color?
  (sm/lazy-validator schema:color))

(def valid-library-color?
  (sm/lazy-validator schema:library-color))

(def check-color
  (sm/check-fn schema:color :hint "expected valid color"))

(def check-library-color
  (sm/check-fn schema:library-color :hint "expected valid color"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn library-color->color
  "Converts a library color data structure to a plain color data structure"
  [lcolor file-id]
  (-> lcolor
      (select-keys [:image :gradient :color :opacity])
      (assoc :ref-id (get lcolor :id))
      (assoc :ref-file file-id)
      (vary-meta assoc
                 :path (get lcolor :path)
                 :name (get lcolor :name))))

;; --- fill

(defn fill->color
  [fill]
  (d/without-nils
   {:color (:fill-color fill)
    :opacity (:fill-opacity fill)
    :gradient (:fill-color-gradient fill)
    :image (:fill-image fill)
    :ref-id (:fill-color-ref-id fill)
    :ref-file (:fill-color-ref-file fill)}))

(defn set-fill-color
  [shape position color opacity gradient image]
  (update-in shape [:fills position]
             (fn [fill]
               (d/without-nils (assoc fill
                                      :fill-color color
                                      :fill-opacity opacity
                                      :fill-color-gradient gradient
                                      :fill-image image)))))

(defn attach-fill-color
  [shape position ref-id ref-file]
  (d/update-in-when shape [:fills position]
                    (fn [fill]
                      (-> fill
                          (assoc :fill-color-ref-file ref-file)
                          (assoc :fill-color-ref-id ref-id)))))

(defn detach-fill-color
  [shape position]
  (d/update-in-when shape [:fills position] dissoc :fill-color-ref-id :fill-color-ref-file))

;; stroke

(defn stroke->color
  [stroke]
  (d/without-nils
   {:color (str/lower (:stroke-color stroke))
    :opacity (:stroke-opacity stroke)
    :gradient (:stroke-color-gradient stroke)
    :image (:stroke-image stroke)
    :ref-id (:stroke-color-ref-id stroke)
    :ref-file (:stroke-color-ref-file stroke)}))

(defn set-stroke-color
  [shape position color opacity gradient image]
  (d/update-in-when shape [:strokes position]
                    (fn [stroke]
                      (-> stroke
                          (assoc :stroke-color color)
                          (assoc :stroke-opacity opacity)
                          (assoc :stroke-color-gradient gradient)
                          (assoc :stroke-image image)
                          (d/without-nils)))))

(defn attach-stroke-color
  [shape position ref-id ref-file]
  (d/update-in-when shape [:strokes position]
                    (fn [stroke]
                      (-> stroke
                          (assoc :stroke-color-ref-id ref-id)
                          (assoc :stroke-color-ref-file ref-file)))))

(defn detach-stroke-color
  [shape position]
  (d/update-in-when shape [:strokes position] dissoc :stroke-color-ref-id :stroke-color-ref-file))

;; shadow

(defn shadow->color
  [shadow]
  (:color shadow))

(defn set-shadow-color
  [shape position color opacity gradient]
  (d/update-in-when shape [:shadow position :color]
                    (fn [shadow-color]
                      (-> shadow-color
                          (assoc :color color)
                          (assoc :opacity opacity)
                          (assoc :gradient gradient)
                          (d/without-nils)))))

(defn attach-shadow-color
  [shape position ref-id ref-file]
  (d/update-in-when shape [:shadow position :color]
                    (fn [color]
                      (-> color
                          (assoc :ref-id ref-id)
                          (assoc :ref-file ref-file)))))

(defn detach-shadow-color
  [shape position]
  (d/update-in-when shape [:shadow position :color] dissoc :ref-id :ref-file))

;; grid

;: FIXME: revisit colors...... WTF
(defn grid->color
  [grid]
  (let [color (-> grid :params :color)]
    (d/without-nils
     {:color (-> color :color)
      :opacity (-> color :opacity)
      :gradient (-> color :gradient)
      :ref-id (-> color :id)
      :ref-file (-> color :file-id)})))

(defn set-grid-color
  [shape position color opacity gradient]
  (d/update-in-when shape [:grids position :params :color]
                    (fn [grid-color]
                      (-> grid-color
                          (assoc :color color)
                          (assoc :opacity opacity)
                          (assoc :gradient gradient)
                          (d/without-nils)))))

(defn attach-grid-color
  [shape position ref-id ref-file]
  (d/update-in-when shape [:grids position :params :color]
                    (fn [color]
                      (-> color
                          (assoc :ref-id ref-id)
                          (assoc :ref-file ref-file)))))

(defn detach-grid-color
  [shape position]
  (d/update-in-when shape [:grids position :params :color] dissoc :ref-id :ref-file))

;; --- Helpers for all colors in a shape

(defn get-text-node-colors
  "Get all colors used by a node of a text shape"
  [node]
  (concat (map fill->color (:fills node))
          (map stroke->color (:strokes node))))

(defn get-all-colors
  "Get all colors used by a shape, in any section."
  [shape]
  (concat (map fill->color (:fills shape))
          (map stroke->color (:strokes shape))
          (map shadow->color (:shadow shape))
          (when (= (:type shape) :frame)
            (map grid->color (:grids shape)))
          (when (= (:type shape) :text)
            (reduce (fn [colors node]
                      (concat colors (get-text-node-colors node)))
                    ()
                    (txt/node-seq (:content shape))))))

(defn uses-library-colors?
  "Check if the shape uses any color in the given library."
  [shape library-id]
  (let [all-colors (get-all-colors shape)]
    (some #(and (some? (:ref-id %))
                (= (:ref-file %) library-id))
          all-colors)))

(defn uses-library-color?
  "Check if the shape uses the given library color."
  [shape library-id color-id]
  (let [all-colors (get-all-colors shape)]
    (some #(and (= (:ref-id %) color-id)
                (= (:ref-file %) library-id))
          all-colors)))

(defn- process-shape-colors
  "Execute an update function on all colors of a shape."
  [shape process-fn]
  (let [process-fill (fn [shape [position fill]]
                       (process-fn shape
                                   position
                                   (fill->color fill)
                                   set-fill-color
                                   attach-fill-color
                                   detach-fill-color))

        process-stroke (fn [shape [position stroke]]
                         (process-fn shape
                                     position
                                     (stroke->color stroke)
                                     set-stroke-color
                                     attach-stroke-color
                                     detach-stroke-color))

        process-shadow (fn [shape [position shadow]]
                         (process-fn shape
                                     position
                                     (shadow->color shadow)
                                     set-shadow-color
                                     attach-shadow-color
                                     detach-shadow-color))

        process-grid (fn [shape [position grid]]
                       (process-fn shape
                                   position
                                   (grid->color grid)
                                   set-grid-color
                                   attach-grid-color
                                   detach-grid-color))

        process-text-node (fn [node]
                            (as-> node $
                              (reduce process-fill $ (d/enumerate (:fills $)))
                              (reduce process-stroke $ (d/enumerate (:strokes $)))))

        process-text (fn [shape]
                       (let [content     (:content shape)
                             new-content (txt/transform-nodes process-text-node content)]
                         (if (not= content new-content)
                           (assoc shape :content new-content)
                           shape)))]

    (as-> shape $
      (reduce process-fill $ (d/enumerate (:fills $)))
      (reduce process-stroke $ (d/enumerate (:strokes $)))
      (reduce process-shadow $ (d/enumerate (:shadow $)))
      (reduce process-grid $ (d/enumerate (:grids $)))
      (process-text $))))

(defn remap-colors
  "Change the shape so that any use of the given color now points to
  the given library."
  [shape library-id color]
  (letfn [(remap-color [shape position shape-color _ attach-fn _]
            (if (= (:ref-id shape-color) (:id color))
              (attach-fn shape
                         position
                         (:id color)
                         library-id)
              shape))]

    (process-shape-colors shape remap-color)))

(defn sync-shape-colors
  "Look for usage of any color of the given library inside the shape,
  and, in this case, copy the library color into the shape."
  [shape library-id library-colors]
  (letfn [(sync-color [shape position shape-color set-fn _ detach-fn]
            (if (= (:ref-file shape-color) library-id)
              (let [library-color (get library-colors (:ref-id shape-color))]
                (if (some? library-color)
                  (set-fn shape
                          position
                          (:color library-color)
                          (:opacity library-color)
                          (:gradient library-color)
                          (:image library-color))
                  (detach-fn shape position)))
              shape))]

    (process-shape-colors shape sync-color)))

(defn- stroke->color-att
  [stroke file-id libraries]
  (let [ref-file      (:stroke-color-ref-file stroke)
        ref-id        (:stroke-color-ref-id stroke)
        shared-colors (dm/get-in libraries [ref-file :data :colors])
        is-shared?    (contains? shared-colors ref-id)
        has-color?    (or (:stroke-color stroke)
                          (:stroke-color-gradient stroke))
        attrs         (cond-> (stroke->color stroke)
                        (not (or is-shared? (= ref-file file-id)))
                        (dissoc :ref-id :ref-file))]
    (when has-color?
      {:attrs attrs
       :prop :stroke
       :shape-id (:shape-id stroke)
       :index (:index stroke)})))

(defn- shadow->color-att
  [shadow file-id libraries]
  (let [color         (get shadow :color)
        ref-file      (get color :ref-file)
        ref-id        (get color :ref-id)
        shared-colors (dm/get-in libraries [ref-file :data :colors])
        is-shared?    (contains? shared-colors ref-id)
        attrs         (cond-> (shadow->color shadow)
                        (not (or is-shared? (= ref-file file-id)))
                        (dissoc :ref-file :ref-id))]
    {:attrs attrs
     :prop :shadow
     :shape-id (:shape-id shadow)
     :index (:index shadow)}))

(defn- text->color-att
  [fill file-id libraries]
  (let [ref-file      (:fill-color-ref-file fill)
        ref-id        (:fill-color-ref-id fill)
        shared-colors (dm/get-in libraries [ref-file :data :colors])
        is-shared?    (contains? shared-colors ref-id)
        attrs         (cond-> (fill->color fill)
                        (not (or is-shared? (= ref-file file-id)))
                        (dissoc :ref-file :ref-id))]

    {:attrs attrs
     :prop :content
     :shape-id (:shape-id fill)
     :index (:index fill)}))

(defn- treat-node
  [node shape-id]
  (map-indexed #(assoc %2 :shape-id shape-id :index %1) node))

(defn- extract-text-colors
  [text file-id libraries]
  (->> (txt/node-seq txt/is-text-node? (:content text))
       (map :fills)
       (mapcat #(treat-node % (:id text)))
       (map #(text->color-att % file-id libraries))))

(defn- fill->color-att
  [fill file-id libraries]
  (let [ref-file      (:fill-color-ref-file fill)
        ref-id        (:fill-color-ref-id fill)
        shared-colors (dm/get-in libraries [ref-file :data :colors])
        is-shared?    (contains? shared-colors ref-id)
        has-color?    (or (:fill-color fill)
                          (:fill-color-gradient fill))
        attrs         (cond-> (fill->color fill)
                        (not (or is-shared? (= ref-file file-id)))
                        (dissoc :ref-file :ref-id))]

    (when has-color?
      {:attrs attrs
       :prop :fill
       :shape-id (:shape-id fill)
       :index (:index fill)})))

(defn extract-all-colors
  [shapes file-id libraries]
  (reduce
   (fn [result shape]
     (let [fill-obj   (map-indexed #(assoc %2 :shape-id (:id shape) :index %1) (:fills shape))
           stroke-obj (map-indexed #(assoc %2 :shape-id (:id shape) :index %1) (:strokes shape))
           shadow-obj (map-indexed #(assoc %2 :shape-id (:id shape) :index %1) (:shadow shape))]
       (if (= :text (:type shape))
         (-> result
             (into (map #(stroke->color-att % file-id libraries)) stroke-obj)
             (into (map #(shadow->color-att % file-id libraries)) shadow-obj)
             (into (extract-text-colors shape file-id libraries)))

         (-> result
             (into (map #(fill->color-att % file-id libraries)) fill-obj)
             (into (map #(stroke->color-att % file-id libraries)) stroke-obj)
             (into (map #(shadow->color-att % file-id libraries)) shadow-obj)))))
   []
   shapes))

(defn colors-seq
  [file-data]
  (vals (:colors file-data)))

(defn- touch
  [color]
  (assoc color :modified-at (dt/now)))

(defn add-color
  [file-data color]
  (update file-data :colors assoc (:id color) (touch color)))

(defn get-color
  [file-data color-id]
  (get-in file-data [:colors color-id]))

(defn get-ref-color
  [library-data color]
  (when (= (:ref-file color) (:id library-data))
    (get-color library-data (:ref-id color))))

(defn set-color
  [file-data color]
  (d/assoc-in-when file-data [:colors (:id color)] (touch color)))

(defn update-color
  [file-data color-id f & args]
  (d/update-in-when file-data [:colors color-id] #(-> (apply f % args)
                                                      (touch))))

(defn delete-color
  [file-data color-id]
  (update file-data :colors dissoc color-id))

(defn used-colors-changed-since
  "Find all usages of any color in the library by the given shape, of colors
   that have ben modified after the date."
  [shape library since-date]
  (->> (get-all-colors shape)
       (keep #(get-ref-color (:data library) %))
       (remove #(< (:modified-at %) since-date))  ;; Note that :modified-at may be nil
       (map (fn [color] {:shape-id (:id shape)
                         :asset-id (:id color)
                         :asset-type :color}))))

