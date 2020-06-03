;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.messages
  (:require
   [rumext.alpha :as mf]
   [uxbox.main.ui.icons :as i]
   [uxbox.main.data.messages :as dm]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.util.data :refer [classnames]]
   [uxbox.util.dom :as dom]
   [uxbox.util.i18n :as i18n :refer [t]]
   [uxbox.util.timers :as ts]))

(defn- type->icon
  [type]
  (case type
    :warning i/msg-warning
    :error i/msg-error
    :success i/msg-success
    :info i/msg-info
    i/msg-error))

(mf/defc notification-item
  [{:keys [type status on-close quick? content] :as props}]
  (let [klass (dom/classnames
               :fixed   true
               :success (= type :success)
               :error   (= type :error)
               :info    (= type :info)
               :warning (= type :warning)
               :hide    (= status :hide)
               :quick   quick?)]
    [:section.banner {:class klass}
     [:div.content
      [:div.icon (type->icon type)]
      [:span content]]
     [:div.btn-close {:on-click on-close} i/close]]))

(mf/defc notifications
  []
  (let [message  (mf/deref refs/message)
        on-close #(st/emit! dm/hide)]
    (when message
      [:& notification-item {:type (:type message)
                             :quick? (boolean (:timeout message))
                             :status (:status message)
                             :content (:content message)
                             :on-close on-close}])))

(mf/defc inline-banner
  {::mf/wrap [mf/memo]}
  [{:keys [type on-close content children] :as props}]
  [:div.inline-banner {:class (dom/classnames
                               :warning (= type :warning)
                               :error   (= type :error)
                               :success (= type :success)
                               :info    (= type :info)
                               :quick   (not on-close))}
   [:div.icon (type->icon type)]
   [:div.content
    [:div.main
     [:span.text content]
     [:div.btn-close {:on-click on-close} i/close]]
    (when children
      [:div.extra
       children])]])

