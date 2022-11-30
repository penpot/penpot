;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.libs.file-builder
  (:require
   [app.common.data :as d]
   [app.common.file-builder :as fb]
   [app.common.uuid :as uuid]
   [app.util.dom :as dom]
   [app.util.zip :as uz]
   [app.worker.export :as e]
   [beicon.core :as rx]
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
             (rx/map #(e/create-manifest (uuid/next) (:id file) :all %))
             (rx/map (fn [a]
                       (vector "manifest.json" a))))

        render-stream
        (->> files-stream
             (rx/flat-map vals)
             (rx/flat-map e/process-pages)
             (rx/observe-on :async)
             (rx/flat-map e/get-page-data)
             (rx/share))

        colors-stream
        (->> files-stream
             (rx/flat-map vals)
             (rx/map #(vector (:id %) (get-in % [:data :colors])))
             (rx/filter #(d/not-empty? (second %)))
             (rx/map e/parse-library-color))

        components-stream
        (->> files-stream
             (rx/flat-map vals)
             (rx/filter #(d/not-empty? (get-in % [:data :components])))
             (rx/flat-map e/parse-library-components))

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
           colors-stream)
          (rx/reduce conj [])
          (rx/with-latest-from files-stream)
          (rx/flat-map (fn [[data _]]
                         (->> (uz/compress-files data)
                              (rx/map #(vector file %)))))))))

(deftype File [^:mutable file]
  Object

  (addPage [_ name]
    (set! file (fb/add-page file {:name name}))
    (str (:current-page-id file)))

  (addPage [_ name options]
    (set! file (fb/add-page file {:name name :options options}))
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

  (asMap [_]
    (clj->js file))

  (export [_]
    (->> (export-file file)
         (rx/subs
          (fn [value]
            (when  (not (contains? value :type))
              (let [[file export-blob] value]
                (dom/trigger-download (:name file) export-blob))))))))

(defn create-file-export [^string name]
  (File. (fb/create-file name)))

(defn exports []
  #js { :createFile    create-file-export })
