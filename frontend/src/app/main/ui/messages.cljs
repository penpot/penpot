;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.messages
  (:require
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.main.data.messages :as dm]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [rumext.alpha :as mf]))

(mf/defc banner
  [{:keys [type position status controls content actions on-close] :as props}]
  [:div.banner {:class (dom/classnames
                         :warning  (= type :warning)
                         :error    (= type :error)
                         :success  (= type :success)
                         :info     (= type :info)
                         :fixed    (= position :fixed)
                         :floating (= position :floating)
                         :inline   (= position :inline)
                         :hide     (= status :hide))}
   [:div.wrapper
     [:div.icon (case type
                  :warning i/msg-warning
                  :error i/msg-error
                  :success i/msg-success
                  :info i/msg-info
                  i/msg-error)]
     [:div.content {:class (dom/classnames
                             :inline-actions (= controls :inline-actions)
                             :bottom-actions (= controls :bottom-actions))}
      content
      (when (or (= controls :bottom-actions) (= controls :inline-actions))
        [:div.actions
          (for [action actions]
            [:div.btn-secondary.btn-small {:key (uuid/next)
                                           :on-click (:callback action)}
             (:label action)])])]
   (when (= controls :close)
     [:div.btn-close {:on-click on-close} i/close])]])

(mf/defc notifications
  []
  (let [message  (mf/deref refs/message)
        on-close (st/emitf dm/hide)]
    (when message
      [:& banner (assoc message
                        :position (or (:position message) :fixed)
                        :controls (if (some? (:controls message))
                                    (:controls message)
                                    (if (some? (:timeout message))
                                      :none
                                      :close))
                        :on-close on-close)])))

(mf/defc inline-banner
  {::mf/wrap [mf/memo]}
  [{:keys [type content on-close actions] :as props}]
  [:& banner {:type type
              :position :inline
              :status :visible
              :controls (if (some? on-close)
                          :close
                          (if (some? actions)
                            :bottom-actions
                            :none))
              :content content
              :on-close on-close
              :actions actions}])

