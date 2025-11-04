;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.page
  "Page options menu entries."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.types.color :as clr]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.undo :as dwu]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.main.ui.workspace.sidebar.options.rows.color-row :refer [color-row*]]
   [app.util.i18n :as i18n :refer [tr]]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def ^:private ref:background-color
  (-> (l/key :background)
      (l/derived refs/workspace-page)))

(mf/defc options*
  {::mf/wrap [mf/memo]}
  []
  (let [background (mf/deref ref:background-color)
        on-change  (mf/use-fn #(st/emit! (dw/change-canvas-color %)))
        on-open    (mf/use-fn #(st/emit! (dwu/start-undo-transaction :options)))
        on-close   (mf/use-fn #(st/emit! (dwu/commit-undo-transaction :options)))

        color      (mf/with-memo [background]
                     {:color (d/nilv background clr/canvas)
                      :opacity 1})]

    [:div {:class (stl/css :element-set)}
     [:div {:class (stl/css :element-title)}
      [:> title-bar* {:collapsable false
                      :title       (tr "workspace.options.canvas-background")
                      :class       (stl/css :title-spacing-page)}]]
     [:div {:class (stl/css :element-content)}

      [:> color-row*
       {:disable-gradient true
        :disable-opacity true
        :disable-image true
        :title (tr "workspace.options.canvas-background")
        :color color
        :on-change on-change
        :origin :canvas
        :on-open on-open
        :on-close on-close}]]]))

