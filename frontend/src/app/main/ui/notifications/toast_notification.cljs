;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.notifications.toast-notification
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

(def ^:private close-icon
  (i/icon-xref :close (stl/css :close-icon)))

(defn get-icon-by-level
  [level]
  (case level
    :warning neutral-icon
    :error error-icon
    :success success-icon
    :info info-icon
    neutral-icon))

(mf/defc toast-notification
  "These are ephemeral elements that disappear when the close button
  is pressed, the page is refreshed, the page is navigated to another
  page or after 7 seconds, which is enough time to be read, except for
  error messages that require user interaction."

  {::mf/props :obj}
  [{:keys [level content on-close links] :as props}]

  [:aside {:class (stl/css-case :toast-notification true
                                :warning  (= level :warning)
                                :error    (= level :error)
                                :success  (= level :success)
                                :info     (= level :info))}

   (get-icon-by-level level)

   [:div {:class (stl/css :text)}
    content
    (when (some? links)
      [:nav {:class (stl/css :link-nav)}
       (for [[index link] (d/enumerate links)]
         [:& lb/link-button {:key (dm/str "link-" index)
                             :class (stl/css :link)
                             :on-click (:callback link)
                             :value (:label link)}])])]



   [:button {:class (stl/css :btn-close)
             :on-click on-close}
    close-icon]])
