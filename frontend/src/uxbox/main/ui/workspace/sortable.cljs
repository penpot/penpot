;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.workspace.sortable
  "A sortable React Hook implementation."
  (:require
   [rumext.alpha :as mf]
   [rumext.util :as mfu]
   [uxbox.util.rdnd :as rdnd]))

;; --- Page Item

(set! *warn-on-infer* true)

(defn use-sortable
  [{:keys [type data on-hover on-drop]
    :or {on-hover (constantly nil)
         on-drop (constantly nil)}
    :as options}]
  (let [ref (mf/use-ref nil)
        [_, drop] (rdnd/useDrop
                   #js {:accept type
                        :hover (fn [item monitor]
                                 (when (mf/ref-val ref)
                                   (on-hover (unchecked-get item "data") monitor)))
                        :drop (fn [item monitor]
                                (when (mf/ref-val ref)
                                  (on-drop (unchecked-get item "data") monitor)))})
        [props, drag] (rdnd/useDrag
                       #js {:item #js {:type type :data data}
                            :collect (fn [^js/ReactDnd.Monitor monitor]
                                       #js {:dragging? (.isDragging monitor)})})]
    [(mfu/obj->map props)
     (drag (drop ref))]))

