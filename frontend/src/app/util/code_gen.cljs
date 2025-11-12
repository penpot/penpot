;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.code-gen
  (:require
   [app.util.code-beautify :as cb]
   [app.util.code-gen.markup-html :as html]
   [app.util.code-gen.markup-svg :as svg]
   [app.util.code-gen.style-css :as css]))

(defn generate-markup-code
  [objects type shapes]
  (let [generate-markup
        (case type
          "html" html/generate-markup
          "svg"  svg/generate-markup)]
    (generate-markup objects shapes)))

(defn generate-formatted-markup-code
  [objects type shapes]
  (let [markup (generate-markup-code objects type shapes)]
    (cb/format-code markup type)))

(defn generate-style-code
  ([objects type root-shapes all-shapes]
   (generate-style-code objects type root-shapes all-shapes nil))
  ([objects type root-shapes all-shapes options]
   (let [generate-style
         (case type
           "css" css/generate-style)]
     (generate-style objects root-shapes all-shapes options))))

(defn prelude
  [type]
  (case type
    "css" css/prelude))
