;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.test-helpers.tokens
  (:require
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.shapes :as ths]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.common.types.pages-list :as ctpl]
   [app.common.types.shape-tree :as ctst]
   [app.common.types.token :as cto]
   [app.common.types.tokens-lib :as ctob]))

(defn get-tokens-lib
  [file]
  (:tokens-lib (ctf/file-data file)))

(defn add-tokens-lib
  [file]
  (ctf/update-file-data file #(update % :tokens-lib ctob/ensure-tokens-lib)))

(defn update-tokens-lib
  [file f]
  (ctf/update-file-data file #(update % :tokens-lib f)))

(defn get-token
  [file set-name token-name]
  (let [tokens-lib (:tokens-lib (:data file))]
    (when tokens-lib
      (-> tokens-lib
          (ctob/get-set set-name)
          (ctob/get-token token-name)))))

(defn token-data-eq?
  "Compare token data without comparing unstable fields."
  [t1 t2]
  (= (dissoc t1 :id :modified-at) (dissoc t2 :id :modified-at)))

(defn- set-stroke-width
  [shape stroke-width]
  (let [strokes (if (seq (:strokes shape))
                  (:strokes shape)
                  [{:stroke-style :solid
                    :stroke-alignment :inner
                    :stroke-width 1
                    :stroke-color "#000000"
                    :stroke-opacity 1}])
        new-strokes (update strokes 0 assoc :stroke-width stroke-width)]
    (ctn/set-shape-attr shape :strokes new-strokes {:ignore-touched true})))

(defn- set-stroke-color
  [shape stroke-color]
  (let [strokes (if (seq (:strokes shape))
                  (:strokes shape)
                  [{:stroke-style :solid
                    :stroke-alignment :inner
                    :stroke-width 1
                    :stroke-color "#000000"
                    :stroke-opacity 1}])
        new-strokes (update strokes 0 assoc :stroke-color stroke-color)]
    (ctn/set-shape-attr shape :strokes new-strokes {:ignore-touched true})))

(defn- set-fill-color
  [shape fill-color]
  (let [fills (if (seq (:fills shape))
                (:fills shape)
                [{:fill-color "#000000"
                  :fill-opacity 1}])
        new-fills (update fills 0 assoc :fill-color fill-color)]
    (ctn/set-shape-attr shape :fills new-fills {:ignore-touched true})))

(defn apply-token-to-shape
  [file shape-label token-name token-attrs shape-attrs resolved-value]
  (let [page   (thf/current-page file)
        shape  (ths/get-shape file shape-label)
        shape' (as-> shape $
                 (cto/apply-token-to-shape {:shape $
                                            :token {:name token-name}
                                            :attributes token-attrs})
                 (reduce (fn [shape attr]
                           (case attr
                             :stroke-width (set-stroke-width shape resolved-value)
                             :stroke-color (set-stroke-color shape resolved-value)
                             :fill (set-fill-color shape resolved-value)
                             (ctn/set-shape-attr shape attr resolved-value {:ignore-touched true})))
                         $
                         shape-attrs))]

    (ctf/update-file-data
     file
     (fn [file-data]
       (ctpl/update-page file-data
                         (:id page)
                         #(ctst/set-shape % shape'))))))
