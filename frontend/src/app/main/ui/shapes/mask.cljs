;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.shapes.mask
  (:require
   [rumext.alpha :as mf]
   [cuerdas.core :as str]))

(defn mask-str [mask]
  (str/fmt "url(#%s)" (str (:id mask) "-mask")))

(defn mask-factory
  [shape-wrapper]
  (mf/fnc mask-shape
    {::mf/wrap-props false}
    [props]
    (let [frame (unchecked-get props "frame")
          mask  (unchecked-get props "mask")]
      [:defs
       [:filter {:id (str (:id mask) "-filter")}
        [:feFlood {:flood-color "white"}]
        [:feComposite {:in "BackgroundImage"
                       :in2 "SourceGraphic"
                       :operator "in"
                       :result "comp"}]]
       [:mask {:id (str (:id mask) "-mask")}
        [:g {:filter (str/fmt "url(#%s)" (str (:id mask) "-filter"))}
         [:& shape-wrapper {:frame frame :shape mask}]]]])))

