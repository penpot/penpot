;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.code-gen.markup-svg
  (:require
   ["react-dom/server" :as rds]
   [app.main.render :as render]
   [app.util.code-beautify :as cb]
   [rumext.v2 :as mf]))

(defn- generate-single-svg
  [objects shape]
  (rds/renderToStaticMarkup
   (mf/element
    render/object-svg*
    #js {:objects objects
         :object-id (-> shape :id)})))

(defn- generate-multi-svg
  [objects shapes]
  (rds/renderToStaticMarkup
   (mf/element
    render/objects-svg*
    #js {:objects objects
         :object-ids (mapv :id shapes)})))

(defn generate-svg
  [objects shape]
  (generate-single-svg objects shape))

(defn generate-markup
  [objects shapes]
  (case (count shapes)
    0 ""
    1 (generate-single-svg objects (first shapes))
    (generate-multi-svg objects shapes)))

(defn generate-formatted-markup
  [objects shapes]
  (-> (generate-markup objects shapes)
      (cb/format-code "svg")))
