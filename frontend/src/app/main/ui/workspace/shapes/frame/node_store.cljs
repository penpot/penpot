;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.shapes.frame.node-store
  (:require
   [app.util.dom :as dom]
   [rumext.v2 :as mf]))

(defn use-node-store
  "Hook responsible of storing the rendered DOM node in memory while not being used"
  [node-ref rendered-ref thumbnail? render-frame?]

  (let [re-render*  (mf/use-state 0)
        parent-ref  (mf/use-ref nil)
        present-ref (mf/use-ref false)

        on-frame-load
        (mf/use-fn
         (fn [node]
           (when (and (some? node)
                      (nil? (mf/ref-val node-ref)))
             (let [content (-> (dom/create-element "http://www.w3.org/2000/svg" "g")
                               (dom/add-class! "frame-content"))]
               (mf/set-ref-val! node-ref content)
               (mf/set-ref-val! parent-ref node)
               (swap! re-render* inc)))))]

    (mf/with-effect [thumbnail? render-frame?]
      (let [rendered? (mf/ref-val rendered-ref)
            present?  (mf/ref-val present-ref)]

        (when (and (true? rendered?)
                   (true? thumbnail?)
                   (false? render-frame?)
                   (true? present?))
          (when-let [parent (mf/ref-val parent-ref)]
            (when-let [node (mf/ref-val node-ref)]
              (dom/remove-child! parent node)
              (mf/set-ref-val! present-ref false)
              (swap! re-render* inc))))

        (when (and (false? present?)
                   (or (false? thumbnail?)
                       (true? render-frame?)))
          (when-let [parent (mf/ref-val parent-ref)]
            (when-let [node (mf/ref-val node-ref)]
              (when-not (dom/child? parent node)
                (dom/append-child! parent node)
                (mf/set-ref-val! present-ref true)
                (swap! re-render* inc)))))))

    on-frame-load))
