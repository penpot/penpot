;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.onboarding
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.config :as cf]
   [app.main.data.events :as ev]
   [app.main.data.modal :as modal]
   [app.main.data.users :as du]
   [app.main.store :as st]
   [app.main.ui.onboarding.newsletter]
   [app.main.ui.onboarding.questions]
   [app.main.ui.onboarding.team-choice]
   [app.main.ui.onboarding.templates]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.timers :as tm]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]))

;; --- ONBOARDING LIGHTBOX

(defn send-event
  [event-name]
  (st/emit! (ptk/event ::ev/event {::ev/name event-name
                                   ::ev/origin "dashboard"})))


(mf/defc onboarding-welcome
  [{:keys [next] :as props}]
  (let [go-next
        (fn []
          (send-event "onboarding-step1-continue")
          (next))]
    [:div {:class (stl/css :modal-container)}
     [:div {:class (stl/css :modal-left)}
      [:img {:src "images/welcomeilustration.svg"
             :border "0"
             :alt (tr "onboarding.welcome.alt")}]]
     [:div {:class (stl/css :modal-right)}
      [:div {:class (stl/css :release)}
       "Version " (:main cf/version)]
      [:h1 {:class (stl/css :modal-title)
            :data-test "onboarding-welcome"}
       (tr "onboarding-v2.welcome.title")]
      [:p {:class (stl/css :modal-text)}
       (tr "onboarding-v2.welcome.desc1")]

      [:div {:class (stl/css :text-wrapper)}
       [:div {:class (stl/css :property-title)}
        [:a {:href "https://community.penpot.app/"
             :target "_blank"
             :on-click #(send-event "onboarding-community-link")}
         (tr "onboarding-v2.welcome.desc2.title")]]
       [:div {:class (stl/css :property-description)}
        (tr "onboarding-v2.welcome.desc2")]]

      [:div {:class (stl/css :text-wrapper)}
       [:div {:class (stl/css :property-title)}
        [:a {:href "https://help.penpot.app/contributing-guide/"
             :target "_blank" :on-click #(send-event "onboarding-contributing-link")}
         (tr "onboarding-v2.welcome.desc3.title")]]
       [:div {:class (stl/css :property-description)}
        (tr "onboarding-v2.welcome.desc3")]]

      [:button {:on-click go-next
                :class (stl/css :accept-btn)
                :data-test "onboarding-next-btn"}
       (tr "labels.continue")]]]))

(mf/defc onboarding-before-start
  [{:keys [next] :as props}]
  (let [go-next
        (fn []
          (send-event "onboarding-step2-continue")
          (next))]
    [:div {:class (stl/css :modal-container)}
     [:div {:class (stl/css :modal-left)}
      [:img {:src "images/beforeyoustartilustration.svg"
             :border "0"
             :alt (tr "onboarding.welcome.alt")}]]
     [:div {:class (stl/css :modal-right)}
      [:div  {:class (stl/css :release)}
       "Version " (:main cf/version)]

      [:h1 {:class (stl/css :modal-title)
            :data-test "onboarding-welcome"}
       (tr "onboarding-v2.before-start.title")]
      [:p {:class (stl/css :modal-text)}
       (tr "onboarding-v2.before-start.desc1")]

      [:div {:class (stl/css :text-wrapper)}
       [:div {:class (stl/css :property-title)}
        [:a {:class (stl/css :modal-link)
             :href "https://help.penpot.app/user-guide/"
             :target "_blank"
             :on-click #(send-event "onboarding-user-guide-link")}
         (tr "onboarding-v2.before-start.desc2.title")]]
       [:div {:class (stl/css :property-description)}
        (tr "onboarding-v2.before-start.desc2")]]

      [:div {:class (stl/css :text-wrapper)}
       [:div {:class (stl/css :property-title)}
        [:a {:class (stl/css :modal-link)
             :href "https://www.youtube.com/c/Penpot"
             :target "_blank"
             :on-click #(send-event "onboarding-video-tutorials-link")}
         (tr "onboarding-v2.before-start.desc3.title")]]
       [:div {:class (stl/css :property-description)}
        (tr "onboarding-v2.before-start.desc3")]]


      [:button {:on-click go-next
                :class (stl/css :accept-btn)
                :data-test "onboarding-next-btn"}
       (tr "labels.continue")]]]))

(mf/defc onboarding-modal
  {::mf/register modal/components
   ::mf/register-as :onboarding}
  [_]
  (let [slide (mf/use-state :start)
        klass (mf/use-state "fadeInDown")

        navigate
        (mf/use-fn #(reset! slide %))

        skip
        (mf/use-fn
         (fn []
           (st/emit! (modal/hide)
                     (du/mark-onboarding-as-viewed))
           (cond
             (contains? cf/flags :onboarding-questions)
             (modal/show! {:type :onboarding-questions})

             (contains? cf/flags :onboarding-newsletter)
             (modal/show! {:type :onboarding-newsletter})

             (contains? cf/flags :onboarding-team)
             (modal/show! {:type :onboarding-team}))))]

    (mf/with-effect [@slide]
      (when (not= :start @slide)
        (reset! klass "fadeIn"))
      (let [sem (tm/schedule 300 #(reset! klass nil))]
        (fn []
          (reset! klass nil)
          (tm/dispose! sem))))

    [:div {:class (stl/css :modal-overlay)}
     [:div.animated {:class (dm/str @klass " " (stl/css :animated))}
      (case @slide
        :start      [:& onboarding-welcome {:next #(navigate :opensource)}]
        :opensource [:& onboarding-before-start {:next skip}])]]))
