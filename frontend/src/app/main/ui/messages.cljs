;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.messages
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.uuid :as uuid]
   [app.main.data.messages :as dmsg]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.link-button :as lb]
   [app.main.ui.icons :as i]
   [rumext.v2 :as mf]))

(mf/defc banner
  [{:keys [type position status controls content links actions on-close data-test role] :as props}]
  [:div {:class (stl/css-case :banner true
                              :warning  (= type :warning)
                              :error    (= type :error)
                              :success  (= type :success)
                              :info     (= type :info)
                              :fixed    (= position :fixed)
                              :floating (= position :floating)
                              :inline   (= position :inline)
                              :hide     (= status :hide))}
   [:div {:class (stl/css :wrapper)}
    [:div {:class (stl/css :icon)}
     (case type
       :warning i/msg-warning-refactor
       :error i/msg-error-refactor
       :success i/msg-success-refactor
       :info i/msg-neutral-refactor
       i/msg-error-refactor)]

    [:div {:class (stl/css-case :content  true
                                :inline-actions (= controls :inline-actions)
                                :bottom-actions (= controls :bottom-actions))
           :data-test data-test
           :role role}
     [:span {:class (stl/css :text)}
      content
      (for [[index link] (d/enumerate links)]
        [:* {:key (dm/str "link-" index)}
         " " [:& lb/link-button {:class (stl/css :link)
                                 :on-click (:callback link)
                                 :value (:label link)}]])]

     (when (or (= controls :bottom-actions) (= controls :inline-actions))

       [:div  {:class (stl/css :actions)}
        (for [action actions]
          [:button {:key (uuid/next)
                    :class (stl/css-case :action-btn true
                                         :primary (= :primary (:type action))
                                         :secondary (= :secondary (:type action))
                                         :danger (= :danger (:type action)))
                    :on-click (:callback action)}
           (:label action)])])]

    (when (= controls :close)
      [:button {:class (stl/css :btn-close)
                :on-click on-close} i/close-refactor])]])

(mf/defc notifications
  []
  (let [message  (mf/deref refs/message)
        on-close #(st/emit! dmsg/hide)]
    (when message
      [:& banner (assoc message
                        :position (or (:position message) :fixed)
                        :controls (if (some? (:controls message))
                                    (:controls message)
                                    :close)
                        :on-close on-close)])))

(mf/defc inline-banner
  {::mf/wrap [mf/memo]}
  [{:keys [type content on-close actions data-test role] :as props}]
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
              :actions actions
              :data-test data-test
              :role role}])

