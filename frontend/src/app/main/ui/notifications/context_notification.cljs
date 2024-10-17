;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.notifications.context-notification
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.main.ui.components.link-button :as lb]
   [app.main.ui.icons :as i]
   [rumext.v2 :as mf]))

(def ^:private neutral-icon
  (i/icon-xref :msg-neutral (stl/css :icon)))

(def ^:private error-icon
  (i/icon-xref :delete-text (stl/css :icon)))

(def ^:private success-icon
  (i/icon-xref :status-tick (stl/css :icon)))

(def ^:private info-icon
  (i/icon-xref :help (stl/css :icon)))

(defn get-icon-by-level
  [level]
  (case level
    :warning neutral-icon
    :error error-icon
    :success success-icon
    :info info-icon
    neutral-icon))

(mf/defc context-notification
  "They are persistent, informative and non-actionable.
  They are contextual messages in specific areas off the app"
  {::mf/props :obj}
  [{:keys [level content links is-html] :as props}]
  [:aside {:class (stl/css-case :context-notification true
                                :contain-html is-html
                                :warning      (= level :warning)
                                :error        (= level :error)
                                :success      (= level :success)
                                :info         (= level :info))}

   (get-icon-by-level level)

   ;; The content can arrive in markdown format, in these cases
   ;;  we will use the prop is-html to true to indicate it and
   ;; that the html injection is performed and the necessary css classes are applied.
   [:div {:class (stl/css :context-text)
          :dangerouslySetInnerHTML (when is-html #js {:__html content})}
    (when-not is-html
      [:*
       content
       (when (some? links)
         (for [[index link] (d/enumerate links)]
                   ;; TODO Review this component
           [:& lb/link-button {:class (stl/css :link)
                               :on-click (:callback link)
                               :value (:label link)
                               :key (dm/str "link-" index)}]))])]])

