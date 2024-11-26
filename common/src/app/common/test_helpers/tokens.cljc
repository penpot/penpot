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

(defn add-tokens-lib
  [file]
  (ctf/update-file-data file #(update % :tokens-lib ctob/ensure-tokens-lib)))

(defn update-tokens-lib
  [file f]
  (ctf/update-file-data file #(update % :tokens-lib f)))

(defn apply-token-to-shape
  [file shape-label token-name token-attrs shape-attrs resolved-value]
  (let [page   (thf/current-page file)
        shape  (ths/get-shape file shape-label)
        shape' (as-> shape $
                 (cto/apply-token-to-shape {:shape $
                                            :token {:name token-name}
                                            :attributes token-attrs})
                 (reduce (fn [shape attr]
                           (ctn/set-shape-attr shape attr resolved-value {:ignore-touched true}))
                         $
                         shape-attrs))]

    (ctf/update-file-data
     file
     (fn [file-data]
       (ctpl/update-page file-data
                         (:id page)
                         #(ctst/set-shape % shape'))))))

