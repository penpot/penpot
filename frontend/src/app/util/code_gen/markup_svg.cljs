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
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(defn generate-svg
  [objects shape]
  (rds/renderToStaticMarkup
   (mf/element
    render/object-svg
    #js {:objects objects
         :object-id (-> shape :id)})))

(defn generate-markup
  [objects shapes]
  (->> shapes
       (map #(generate-svg objects %))
       (str/join "\n")))

(defn generate-formatted-markup
  [objects shapes]
  (let [markup (generate-markup objects shapes)]
    (cb/format-code markup "svg")))
