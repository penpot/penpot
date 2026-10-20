; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.viewport.top-bar
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.persistence :as dps]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.common :as dwc]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.workspace.viewport.grid-layout-editor :refer [grid-edition-actions*]]
   [app.main.ui.workspace.viewport.path-actions :refer [path-actions*]]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

;; FIXME: this namespace should be renamed and all translation files
;; should also be renamed. But this should be done on development
;; branch.

(mf/defc view-only-bar*
  []
  (let [on-close
        (mf/use-fn
         (fn []
           (st/emit! :interrupt
                     (dw/set-options-mode :design)
                     (dwc/set-workspace-read-only false))))
        render-context-lost? (mf/deref refs/render-context-lost?)
        save-locked?         (some? (mf/deref refs/persistence-lock))
        persistence          (mf/deref refs/persistence)]
    [:div {:class (stl/css-case :viewport-actions true
                                :viewport-actions-save-failed save-locked?)}
     [:div {:class (stl/css :viewport-actions-container)}
      [:div {:class (stl/css :viewport-actions-title)
             :role (when save-locked? "status")}
       [:> i18n/tr-html*
        {:tag-name "span"
         :content (cond
                    save-locked?
                    (if (dps/save-retrying? persistence)
                      (tr "workspace.save-failure.retrying")
                      (tr "workspace.save-failure.stopped"))

                    render-context-lost?
                    (tr "workspace.top-bar.webgl-context-lost")

                    :else
                    (tr "workspace.top-bar.view-only"))}]]
      (cond
        save-locked?
        nil

        render-context-lost?
        [:> button* {:variant "primary" :on-click (fn [] (js/location.reload))}
         (tr "workspace.top-bar.webgl-context-lost.reload")]

        :else
        [:> button* {:on-click on-close}
         (tr "workspace.top-bar.read-only.done")])]]))

(mf/defc path-edition-bar*
  [{:keys [layout edit-path-state shape]}]
  (let [rulers? (contains? layout :rulers)
        class   (stl/css-case
                 :viewport-actions-path true
                 :viewport-actions-no-rulers (not rulers?))]
    [:div {:class class}
     [:> path-actions* {:shape shape :state edit-path-state}]]))

(mf/defc grid-edition-bar*
  [{:keys [shape]}]
  [:> grid-edition-actions* {:shape shape}])

(mf/defc edition-bars*
  [{:keys [layout
           path-editing
           path-drawing
           path-state
           path-shape
           grid-editing
           grid-shape
           single-select]}]
  [:*
   (when (or (and ^boolean path-editing ^boolean single-select)
             (and ^boolean path-drawing (some? path-state)))
     [:> path-edition-bar* {:shape path-shape
                            :edit-path-state path-state
                            :layout layout}])

   (when (and ^boolean grid-editing ^boolean single-select)
     [:> grid-edition-bar* {:shape grid-shape}])])

