;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.shapes.group
  (:require
   [app.common.data.macros :as dm]
   [app.main.ui.context :as muc]
   [app.main.ui.shapes.mask :refer [mask-url clip-url mask-factory]]
   [rumext.v2 :as mf]))

(defn group-shape
  [shape-wrapper]
  (let [render-mask (mask-factory shape-wrapper)]
    (mf/fnc group-shape
      {::mf/wrap-props false}
      [props]
      (let [shape         (unchecked-get props "shape")
            childs        (unchecked-get props "childs")
            render-id     (mf/use-ctx muc/render-id)
            masked-group? (:masked-group shape)

            mask          (if ^boolean masked-group?
                            (first childs)
                            nil)
            childs        (if ^boolean masked-group?
                            (rest childs)
                            childs)

            wrapper       (if ^boolean masked-group? "g" mf/Fragment)
            clip-props    (if ^boolean masked-group?
                            #js {:clipPath (clip-url render-id mask)}
                            #js {})

            mask-props    (if ^boolean masked-group?
                            #js {:mask (mask-url render-id mask)}
                            #js {})]

        ;; We need to separate mask and clip into two because a bug in
        ;; Firefox breaks when the group has clip+mask+foreignObject
        ;; Clip and mask separated will work in every platform Firefox
        ;; bug: https://bugzilla.mozilla.org/show_bug.cgi?id=1734805
        [:> wrapper clip-props
         [:> wrapper mask-props
          (when ^boolean masked-group?
            [:& render-mask {:mask mask}])

          (for [item childs]
            [:& shape-wrapper
             {:shape item
              :key (dm/str (dm/get-prop item :id))}])]]))))



