; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.viewport.top-bar
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.types.shape.layout :as ctl]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.common :as dwc]
   [app.main.store :as st]
   [app.main.ui.workspace.top-toolbar :refer [top-toolbar]]
   [app.main.ui.workspace.viewport.grid-layout-editor :refer [grid-edition-actions]]
   [app.main.ui.workspace.viewport.path-actions :refer [path-actions*]]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(mf/defc view-only-actions*
  []
  (let [handle-close-view-mode
        (mf/use-fn
         (fn []
           (st/emit! :interrupt
                     (dw/set-options-mode :design)
                     (dwc/set-workspace-read-only false))))]
    [:div {:class (stl/css :viewport-actions)}
     [:div {:class (stl/css :viewport-actions-container)}
      [:div {:class (stl/css :viewport-actions-title)}
       [:> i18n/tr-html*
        {:tag-name "span"
         :content (tr "workspace.top-bar.view-only")}]]
      [:button {:class (stl/css :done-btn)
                :on-click handle-close-view-mode}
       (tr "workspace.top-bar.read-only.done")]]]))

(mf/defc top-bar*
  [{:keys [layout drawing is-read-only edition selected edit-path]}]
  (let [rulers?     (contains? layout :rulers)
        hide-ui?    (contains? layout :hide-ui)

        drawing-obj (get drawing :object)
        shape       (or drawing-obj (-> selected first))
        shape-id    (dm/get-prop shape :id)

        single?     (= (count selected) 1)
        editing?    (= shape-id edition)

        draw-path?  (and (some? drawing-obj)
                         (cfh/path-shape? drawing-obj)
                         (not= :curve (:tool drawing)))

        is-path-edition
        (or (and single? editing?
                 (and (not (cfh/text-shape? shape))
                      (not (cfh/frame-shape? shape))))
            draw-path?)

        grid-edition?
        (and single? editing? (ctl/grid-layout? shape))]

    [:*
     (when-not ^boolean hide-ui?
       [:& top-toolbar {:layout layout}])

     (cond
       ^boolean
       is-read-only
       [:> view-only-actions*]

       ^boolean
       is-path-edition
       [:div {:class (stl/css-case :viewport-actions-path true :viewport-actions-no-rulers (not rulers?))}
        [:> path-actions* {:shape shape :edit-path edit-path}]]

       grid-edition?
       [:& grid-edition-actions {:shape shape}])]))
