; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.viewport.top-bar
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.files.helpers :as cfh]
   [app.common.types.shape.layout :as ctl]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.common :as dwc]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.context :as ctx]
   [app.main.ui.workspace.top-toolbar :refer [top-toolbar]]
   [app.main.ui.workspace.viewport.grid-layout-editor :refer [grid-edition-actions]]
   [app.main.ui.workspace.viewport.path-actions :refer [path-actions]]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(mf/defc view-only-actions
  []
  (let [handle-close-view-mode
        (mf/use-callback
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

(mf/defc top-bar
  {::mf/wrap [mf/memo]}
  [{:keys [layout]}]
  (let [edition     (mf/deref refs/selected-edition)
        selected    (mf/deref refs/selected-objects)
        drawing     (mf/deref refs/workspace-drawing)
        rulers?     (mf/deref refs/rulers?)
        drawing-obj (:object drawing)
        shape       (or drawing-obj (-> selected first))

        single? (= (count selected) 1)
        editing? (= (:id shape) edition)
        draw-path? (and (some? drawing-obj)
                        (cfh/path-shape? drawing-obj)
                        (not= :curve (:tool drawing)))

        workspace-read-only? (mf/use-ctx ctx/workspace-read-only?)
        hide-ui?       (:hide-ui layout)

        path-edition? (or (and single? editing?
                               (and (not (cfh/text-shape? shape))
                                    (not (cfh/frame-shape? shape))))
                          draw-path?)

        grid-edition? (and single? editing? (ctl/grid-layout? shape))]

    [:*
     (when-not hide-ui?
       [:& top-toolbar {:layout layout}])

     (cond
       workspace-read-only?
       [:& view-only-actions]

       path-edition?
       [:div {:class (stl/css-case :viewport-actions-path true :viewport-actions-no-rulers (not rulers?))}
        [:& path-actions {:shape shape}]]

       grid-edition?
       [:& grid-edition-actions {:shape shape}])]))
