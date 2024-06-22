;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.libs.file-builder
  (:require
   [app.common.data :as d]
   [app.common.features :as cfeat]
   [app.common.files.builder :as fb]
   [app.common.media :as cm]
   [app.common.types.components-list :as ctkl]
   [app.common.uuid :as uuid]
   [app.util.dom :as dom]
   [app.util.json :as json]
   [app.util.webapi :as wapi]
   [app.util.zip :as uz]
   [app.worker.export :as e]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]))

(defn parse-data [data]
  (as-> data $
    (js->clj $ :keywordize-keys true)
    ;; Transforms camelCase to kebab-case
    (d/deep-mapm
     (fn [[key value]]
       (let [value (if (= (type value) js/Symbol)
                     (keyword (js/Symbol.keyFor value))
                     value)
             key (-> key d/name str/kebab keyword)]
         [key value])) $)))

(defn data-uri->blob
  [data-uri]
  (let [[mtype b64-data] (str/split data-uri ";base64,")
        mtype   (subs mtype (inc (str/index-of mtype ":")))
        decoded (.atob js/window b64-data)
        size    (.-length ^js decoded)
        content (js/Uint8Array. size)]

    (doseq [i (range 0 size)]
      (aset content i (.charCodeAt decoded i)))

    (wapi/create-blob content mtype)))

(defn parse-library-media
  [[file-id media]]
  (rx/merge
   (let [markup
         (->> (vals media)
              (reduce e/collect-media {})
              (json/encode))]
     (rx/of (vector (str file-id "/media.json") markup)))

   (->> (rx/from (vals media))
        (rx/map #(assoc % :file-id file-id))
        (rx/merge-map
         (fn [media]
           (let [file-path (str/concat file-id "/media/" (:id media) (cm/mtype->extension (:mtype media)))
                 blob (data-uri->blob (:uri media))]
             (rx/of (vector file-path blob))))))))

(defn export-file
  [file]
  (let [file (assoc file
                    :name (:name file)
                    :file-name (:name file)
                    :is-shared false)

        files-stream (->> (rx/of {(:id file) file})
                          (rx/share))

        manifest-stream
        (->> files-stream
             (rx/map #(e/create-manifest (uuid/next) (:id file) :all % cfeat/default-features))
             (rx/map (fn [a]
                       (vector "manifest.json" a))))

        render-stream
        (->> files-stream
             (rx/merge-map vals)
             (rx/merge-map e/process-pages)
             (rx/observe-on :async)
             (rx/merge-map e/get-page-data)
             (rx/share))

        colors-stream
        (->> files-stream
             (rx/merge-map vals)
             (rx/map #(vector (:id %) (get-in % [:data :colors])))
             (rx/filter #(d/not-empty? (second %)))
             (rx/map e/parse-library-color))

        typographies-stream
        (->> files-stream
             (rx/merge-map vals)
             (rx/map #(vector (:id %) (get-in % [:data :typographies])))
             (rx/filter #(d/not-empty? (second %)))
             (rx/map e/parse-library-typographies))

        media-stream
        (->> files-stream
             (rx/merge-map vals)
             (rx/map #(vector (:id %) (get-in % [:data :media])))
             (rx/filter #(d/not-empty? (second %)))
             (rx/merge-map parse-library-media))

        components-stream
        (->> files-stream
             (rx/merge-map vals)
             (rx/filter #(d/not-empty? (ctkl/components-seq (:data %))))
             (rx/merge-map e/parse-library-components))

        pages-stream
        (->> render-stream
             (rx/map e/collect-page))]

    (rx/merge
     (->> render-stream
          (rx/map #(hash-map
                    :type :progress
                    :file (:id file)
                    :data (str "Render " (:file-name %) " - " (:name %)))))

     (->> (rx/merge
           manifest-stream
           pages-stream
           components-stream
           media-stream
           colors-stream
           typographies-stream)
          (rx/reduce conj [])
          (rx/with-latest-from files-stream)
          (rx/merge-map (fn [[data _]]
                          (->> (uz/compress-files data)
                               (rx/map #(vector file %)))))))))

(deftype File [^:mutable file]
  Object

  (addPage [_ name]
    (set! file (fb/add-page file {:name name}))
    (str (:current-page-id file)))

  (addPage [_ name options]
    (set! file (fb/add-page file {:name name :options (parse-data options)}))
    (str (:current-page-id file)))

  (closePage [_]
    (set! file (fb/close-page file)))

  (addArtboard [_ data]
    (set! file (fb/add-artboard file (parse-data data)))
    (str (:last-id file)))

  (closeArtboard [_]
    (set! file (fb/close-artboard file)))

  (addGroup [_ data]
    (set! file (fb/add-group file (parse-data data)))
    (str (:last-id file)))

  (closeGroup [_]
    (set! file (fb/close-group file)))

  (addBool [_ data]
    (set! file (fb/add-bool file (parse-data data)))
    (str (:last-id file)))

  (closeBool [_]
    (set! file (fb/close-bool file)))

  (createRect [_ data]
    (set! file (fb/create-rect file (parse-data data)))
    (str (:last-id file)))

  (createCircle [_ data]
    (set! file (fb/create-circle file (parse-data data)))
    (str (:last-id file)))

  (createPath [_ data]
    (set! file (fb/create-path file (parse-data data)))
    (str (:last-id file)))

  (createText [_ data]
    (set! file (fb/create-text file (parse-data data)))
    (str (:last-id file)))

  (createImage [_ data]
    (set! file (fb/create-image file (parse-data data)))
    (str (:last-id file)))

  (createSVG [_ data]
    (set! file (fb/create-svg-raw file (parse-data data)))
    (str (:last-id file)))

  (closeSVG [_]
    (set! file (fb/close-svg-raw file)))

  (addLibraryColor [_ data]
    (set! file (fb/add-library-color file (parse-data data)))
    (str (:last-id file)))

  (updateLibraryColor [_ data]
    (set! file (fb/update-library-color file (parse-data data)))
    (str (:last-id file)))

  (deleteLibraryColor [_ data]
    (set! file (fb/delete-library-color file (parse-data data)))
    (str (:last-id file)))

  (addLibraryMedia [_ data]
    (set! file (fb/add-library-media file (parse-data data)))
    (str (:last-id file)))

  (deleteLibraryMedia [_ data]
    (set! file (fb/delete-library-media file (parse-data data)))
    (str (:last-id file)))

  (addLibraryTypography [_ data]
    (set! file (fb/add-library-typography file (parse-data data)))
    (str (:last-id file)))

  (deleteLibraryTypography [_ data]
    (set! file (fb/delete-library-typography file (parse-data data)))
    (str (:last-id file)))

  (startComponent [_ data]
    (set! file (fb/start-component file (parse-data data)))
    (str (:current-component-id file)))

  (finishComponent [_]
    (set! file (fb/finish-component file)))

  (createComponentInstance [_ data]
    (set! file (fb/create-component-instance file (parse-data data)))
    (str (:last-id file)))

  (lookupShape [_ shape-id]
    (clj->js (fb/lookup-shape file (uuid/uuid shape-id))))

  (updateObject [_ id new-obj]
    (let [old-obj (fb/lookup-shape file (uuid/uuid id))
          new-obj (d/deep-merge old-obj (parse-data new-obj))]
      (set! file (fb/update-object file old-obj new-obj))))

  (deleteObject [_ id]
    (set! file (fb/delete-object file (uuid/uuid id))))

  (getId [_]
    (:id file))

  (getCurrentPageId [_]
    (:current-page-id file))

  (asMap [_]
    (clj->js file))

  (newId [_]
    (uuid/next))

  (export [_]
    (->> (export-file file)
         (rx/subs!
          (fn [value]
            (when  (not (contains? value :type))
              (let [[file export-blob] value]
                (dom/trigger-download (:name file) export-blob))))))))

(defn create-file-export [^string name]
  (binding [cfeat/*current* cfeat/default-features]
    (File. (fb/create-file name))))

(def tokens-json
  {:core
   {:dimension
    {:scale {:value "2" :type "dimension"}
     :xs {:value "4" :type "dimension"}
     :sm {:value "{dimension.xs} * {dimension.scale}" :type "dimension"}
     :md {:value "{dimension.sm} * {dimension.scale}" :type "dimension"}
     :lg {:value "{dimension.md} * {dimension.scale}" :type "dimension"}
     :xl {:value "{dimension.lg} * {dimension.scale}" :type "dimension"}}
    :spacing
    {:xs {:value "{dimension.xs}" :type "spacing"}
     :sm {:value "{dimension.sm}" :type "spacing"}
     :md {:value "{dimension.md}" :type "spacing"}
     :lg {:value "{dimension.lg}" :type "spacing"}
     :xl {:value "{dimension.xl}" :type "spacing"}
     :multi-value {:value "{dimension.sm} {dimension.xl}" :type "spacing"
                   :description "You can have multiple values in a single spacing token. Read more on these: https://docs.tokens.studio/available-tokens/spacing-tokens#multi-value-spacing-tokens"}}
    :borderRadius
    {:sm {:value "4" :type "borderRadius"}
     :lg {:value "8" :type "borderRadius"}
     :xl {:value "16" :type "borderRadius"}
     :multi-value {:value "{borderRadius.sm} {borderRadius.lg}" :type "borderRadius"
                   :description "You can have multiple values in a single radius token. Read more on these: https://docs.tokens.studio/available-tokens/border-radius-tokens#single--multiple-values"}}
    :colors
    {:black {:value "#000000" :type "color"}
     :white {:value "#ffffff" :type "color"}
     :gray
     {"100" {:value "#f7fafc" :type "color"}
      "200" {:value "#edf2f7" :type "color"}
      "300" {:value "#e2e8f0" :type "color"}
      "400" {:value "#cbd5e0" :type "color"}
      "500" {:value "#a0aec0" :type "color"}
      "600" {:value "#718096" :type "color"}
      "700" {:value "#4a5568" :type "color"}
      "800" {:value "#2d3748" :type "color"}
      "900" {:value "#1a202c" :type "color"}}
     :red
     {"100" {:value "#fff5f5" :type "color"}
      "200" {:value "#fed7d7" :type "color"}
      "300" {:value "#feb2b2" :type "color"}
      "400" {:value "#fc8181" :type "color"}
      "500" {:value "#f56565" :type "color"}
      "600" {:value "#e53e3e" :type "color"}
      "700" {:value "#c53030" :type "color"}
      "800" {:value "#9b2c2c" :type "color"}
      "900" {:value "#742a2a" :type "color"}}
     :orange
     {"100" {:value "#fffaf0" :type "color"}
      "200" {:value "#feebc8" :type "color"}
      "300" {:value "#fbd38d" :type "color"}
      "400" {:value "#f6ad55" :type "color"}
      "500" {:value "#ed8936" :type "color"}
      "600" {:value "#dd6b20" :type "color"}
      "700" {:value "#c05621" :type "color"}
      "800" {:value "#9c4221" :type "color"}
      "900" {:value "#7b341e" :type "color"}}
     :yellow
     {"100" {:value "#fffff0" :type "color"}
      "200" {:value "#fefcbf" :type "color"}
      "300" {:value "#faf089" :type "color"}
      "400" {:value "#f6e05e" :type "color"}
      "500" {:value "#ecc94b" :type "color"}
      "600" {:value "#d69e2e" :type "color"}
      "700" {:value "#b7791f" :type "color"}
      "800" {:value "#975a16" :type "color"}
      "900" {:value "#744210" :type "color"}}
     :green
     {"100" {:value "#f0fff4" :type "color"}
      "200" {:value "#c6f6d5" :type "color"}
      "300" {:value "#9ae6b4" :type "color"}
      "400" {:value "#68d391" :type "color"}
      "500" {:value "#48bb78" :type "color"}
      "600" {:value "#38a169" :type "color"}
      "700" {:value "#2f855a" :type "color"}
      "800" {:value "#276749" :type "color"}
      "900" {:value "#22543d" :type "color"}}
     :teal
     {"100" {:value "#e6fffa" :type "color"}
      "200" {:value "#b2f5ea" :type "color"}
      "300" {:value "#81e6d9" :type "color"}
      "400" {:value "#4fd1c5" :type "color"}
      "500" {:value "#38b2ac" :type "color"}
      "600" {:value "#319795" :type "color"}
      "700" {:value "#2c7a7b" :type "color"}
      "800" {:value "#285e61" :type "color"}
      "900" {:value "#234e52" :type "color"}}
     :blue
     {"100" {:value "#ebf8ff" :type "color"}
      "200" {:value "#bee3f8" :type "color"}
      "300" {:value "#90cdf4" :type "color"}
      "400" {:value "#63b3ed" :type "color"}
      "500" {:value "#4299e1" :type "color"}
      "600" {:value "#3182ce" :type "color"}
      "700" {:value "#2b6cb0" :type "color"}
      "800" {:value "#2c5282" :type "color"}
      "900" {:value "#2a4365" :type "color"}}
     :indigo
     {"100" {:value "#ebf4ff" :type "color"}
      "200" {:value "#c3dafe" :type "color"}
      "300" {:value "#a3bffa" :type "color"}
      "400" {:value "#7f9cf5" :type "color"}
      "500" {:value "#667eea" :type "color"}
      "600" {:value "#5a67d8" :type "color"}
      "700" {:value "#4c51bf" :type "color"}
      "800" {:value "#434190" :type "color"}
      "900" {:value "#3c366b" :type "color"}}
     :purple
     {"100" {:value "#faf5ff" :type "color"}
      "200" {:value "#e9d8fd" :type "color"}
      "300" {:value "#d6bcfa" :type "color"}
      "400" {:value "#b794f4" :type "color"}
      "500" {:value "#9f7aea" :type "color"}
      "600" {:value "#805ad5" :type "color"}
      "700" {:value "#6b46c1" :type "color"}
      "800" {:value "#553c9a" :type "color"}
      "900" {:value "#44337a" :type "color"}}
     :pink
     {"100" {:value "#fff5f7" :type "color"}
      "200" {:value "#fed7e2" :type "color"}
      "300" {:value "#fbb6ce" :type "color"}
      "400" {:value "#f687b3" :type "color"}
      "500" {:value "#ed64a6" :type "color"}
      "600" {:value "#d53f8c" :type "color"}
      "700" {:value "#b83280" :type "color"}
      "800" {:value "#97266d" :type "color"}
      "900" {:value "#702459" :type "color"}}}
    :opacity
    {:low {:value "10%" :type "opacity"}
     :md {:value "50%" :type "opacity"}
     :high {:value "90%" :type "opacity"}}
    :fontFamilies
    {:heading {:value "Inter" :type "fontFamilies"}
     :body {:value "Roboto" :type "fontFamilies"}}
    :lineHeights
    {:heading {:value "110%" :type "lineHeights"}
     :body {:value "140%" :type "lineHeights"}}
    :letterSpacing
    {:default {:value "0" :type "letterSpacing"}
     :increased {:value "150%" :type "letterSpacing"}
     :decreased {:value "-5%" :type "letterSpacing"}}
    :paragraphSpacing
    {:h1 {:value "32" :type "paragraphSpacing"}
     :h2 {:value "26" :type "paragraphSpacing"}}
    :fontWeights
    {:headingRegular {:value "Regular" :type "fontWeights"}
     :headingBold {:value "Bold" :type "fontWeights"}
     :bodyRegular {:value "Regular" :type "fontWeights"}
     :bodyBold {:value "Bold" :type "fontWeights"}}
    :fontSizes
    {:h1 {:value "{fontSizes.h2} * 1.25" :type "fontSizes"}
     :h2 {:value "{fontSizes.h3} * 1.25" :type "fontSizes"}
     :h3 {:value "{fontSizes.h4} * 1.25" :type "fontSizes"}
     :h4 {:value "{fontSizes.h5} * 1.25" :type "fontSizes"}
     :h5 {:value "{fontSizes.h6} * 1.25" :type "fontSizes"}
     :h6 {:value "{fontSizes.body} * 1" :type "fontSizes"}
     :body {:value "16" :type "fontSizes"}
     :sm {:value "{fontSizes.body} * 0.85" :type "fontSizes"}
     :xs {:value "{fontSizes.body} * 0.65" :type "fontSizes"}}}})

(defn export-tokens-file [tokens-json]
  (let [file-name "tokens.json"
        file-content (json/encode-tokens tokens-json)
        blob (wapi/create-blob (clj->js file-content) "application/json")]
    (dom/trigger-download file-name blob)))

(defn exports []
  #js {:createFile    create-file-export
       :exportTokens export-tokens-file})
