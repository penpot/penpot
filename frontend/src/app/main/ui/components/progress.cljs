;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.components.progress
  "Assets exportation common components."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.types.color :as clr]
   [app.main.data.common :as dcm]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.icons :as deprecated-icons]
   [app.util.i18n :as i18n :refer  [tr]]
   [app.util.theme :as theme]
   [rumext.v2 :as mf]))

(def ^:private neutral-icon
  (deprecated-icons/icon-xref :msg-neutral (stl/css :icon)))

(def ^:private error-icon
  (deprecated-icons/icon-xref :delete-text (stl/css :icon)))

(def ^:private close-icon
  (deprecated-icons/icon-xref :close (stl/css :close-icon)))

(mf/defc progress-notification-widget*
  []
  (let [state          (mf/deref refs/progress)
        profile        (mf/deref refs/profile)
        theme          (get profile :theme theme/default)
        default-theme? (= theme/default theme)
        error?         (:error state)
        healthy?       (:healthy state)
        visible?       (:visible state)
        progress       (:progress state)
        hint           (:hint state)
        total          (:total state)

        pwidth
        (if error?
          280
          (/ (* progress 280) total))

        color
        (cond
          error?         clr/new-danger
          healthy?       (if default-theme?
                           clr/new-primary
                           clr/new-primary-light)
          (not healthy?) clr/new-warning)

        background-clr
        (if default-theme?
          clr/background-quaternary
          clr/background-quaternary-light)

        toggle-detail-visibility
        (mf/use-fn
         (fn []
           (st/emit! (dcm/toggle-progress-visibility))))]

    [:*
     (when visible?
       [:div {:class (stl/css-case :progress-modal true
                                   :has-error error?)}
        (if error?
          error-icon
          neutral-icon)

        [:div {:class (stl/css :title)}
         [:div {:class (stl/css :title-text)} hint]
         (if error?
           [:button {:class (stl/css :retry-btn)
                     ;; :on-click retry-last-operation
                     }
            (tr "labels.retry")]

           [:span {:class (stl/css :progress)}
            (dm/str progress " / " total)])]

        [:button {:class (stl/css :progress-close-button)
                  :on-click toggle-detail-visibility}
         close-icon]

        (when-not error?
          [:svg {:class (stl/css :progress-bar)
                 :height 4
                 :width 280}
           [:g
            [:path {:d "M0 0 L280 0"
                    :stroke background-clr
                    :stroke-width 30}]
            [:path {:d (dm/str "M0 0 L280 0")
                    :stroke color
                    :stroke-width 30
                    :fill "transparent"
                    :stroke-dasharray 280
                    :stroke-dashoffset (- 280 pwidth)
                    :style {:transition "stroke-dashoffset 1s ease-in-out"}}]]])])]))
