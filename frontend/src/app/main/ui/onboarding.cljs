;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.onboarding
  (:require
   [app.config :as cf]
   [app.main.data.modal :as modal]
   [app.main.data.users :as du]
   [app.main.store :as st]
   [app.main.ui.onboarding.newsletter]
   [app.main.ui.onboarding.questions]
   [app.main.ui.onboarding.team-choice]
   [app.main.ui.onboarding.templates]
   [app.main.ui.releases.common :as rc]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.timers :as tm]
   [rumext.alpha :as mf]))

;; --- ONBOARDING LIGHTBOX

(mf/defc onboarding-start
  [{:keys [next] :as props}]
  [:div.modal-container.onboarding
   [:div.modal-left.welcome
    [:img {:src "images/login-on.jpg" :border "0" :alt (tr "onboarding.welcome.alt")}]]
   [:div.modal-right
    [:div.modal-title
     [:h2 {:data-test "onboarding-welcome"} (tr "onboarding.welcome.title")]]
    [:span.release "Beta version " (:main @cf/version)]
    [:div.modal-content
     [:p (tr "onboarding.welcome.desc1")]
     [:p (tr "onboarding.welcome.desc2")]
     [:p (tr "onboarding.welcome.desc3")]]
    [:div.modal-navigation
     [:button.btn-secondary {:on-click next :data-test "onboarding-next-btn"} (tr "labels.continue")]]]
   [:img.deco {:src "images/deco-left.png" :border "0"}]
   [:img.deco.right {:src "images/deco-right.png" :border "0"}]])

(mf/defc onboarding-opensource
  [{:keys [next] :as props}]
  [:div.modal-container.onboarding.black
   [:div.modal-left
    [:img {:src "images/open-source.svg" :border "0" :alt (tr "onboarding.contrib.alt")}]]
   [:div.modal-right
    [:div.modal-title
     [:h2 (tr "onboarding.contrib.title")]]
    [:div.modal-content
     [:p (tr "onboarding.contrib.desc1")]
     [:p
      (tr "onboarding.contrib.desc2.1")
      "\u00A0"
      [:a {:href "https://github.com/penpot" :target "_blank"} (tr "onboarding.contrib.link")]
      "\u00A0"
      (tr "onboarding.contrib.desc2.2")]]
    [:div.modal-navigation
     [:button.btn-secondary {:on-click next  :data-test "opsource-next-btn"} (tr "labels.continue")]]]])

(defmulti render-slide :slide)

(defmethod render-slide 0
  [{:keys [navigate skip slide] :as props}]
  (mf/html
   [:div.modal-container.onboarding.feature
    [:div.modal-left
     [:img {:src "images/on-design.gif" :border "0" :alt (tr "onboarding.slide.0.alt")}]]
    [:div.modal-right
     [:div.modal-title
      [:h2 {:data-test "slide-0-title"} (tr "onboarding.slide.0.title")]]
     [:div.modal-content
      [:p (tr "onboarding.slide.0.desc1")]
      [:p (tr "onboarding.slide.0.desc2")]]
     [:div.modal-navigation
      [:button.btn-secondary {:on-click #(navigate 1)
                              :data-test "slide-0-btn"} (tr "labels.continue")]
      [:span.skip {:on-click skip :data-test "skip-btn"} (tr "labels.skip")]
      [:& rc/navigation-bullets
       {:slide slide
        :navigate navigate
        :total 4}]]]]))

(defmethod render-slide 1
  [{:keys [navigate slide skip] :as props}]
  (mf/html
   [:div.modal-container.onboarding.feature
    [:div.modal-left
     [:img {:src "images/on-proto.gif" :border "0" :alt (tr "onboarding.slide.1.alt")}]]
    [:div.modal-right
     [:div.modal-title
      [:h2 {:data-test "slide-1-title"} (tr "onboarding.slide.1.title")]]
     [:div.modal-content
      [:p (tr "onboarding.slide.1.desc1")]
      [:p (tr "onboarding.slide.1.desc2")]]
     [:div.modal-navigation
      [:button.btn-secondary {:on-click #(navigate 2)
                              :data-test "slide-1-btn"} (tr "labels.continue")]
      [:span.skip {:on-click skip :data-test "skip-btn"} (tr "labels.skip")]
      [:& rc/navigation-bullets
       {:slide slide
        :navigate navigate
        :total 4}]]]]))

(defmethod render-slide 2
  [{:keys [navigate slide skip] :as props}]
  (mf/html
   [:div.modal-container.onboarding.feature
    [:div.modal-left
     [:img {:src "images/on-feed.gif" :border "0" :alt (tr "onboarding.slide.2.alt")}]]
    [:div.modal-right
     [:div.modal-title
      [:h2 {:data-test "slide-2-title"} (tr "onboarding.slide.2.title")]]
     [:div.modal-content
      [:p (tr "onboarding.slide.2.desc1")]]
     [:div.modal-navigation
      [:button.btn-secondary {:on-click #(navigate 3)
                              :data-test "slide-2-btn"} (tr "labels.continue")]
      [:span.skip {:on-click skip :data-test "skip-btn"} (tr "labels.skip")]
      [:& rc/navigation-bullets
       {:slide slide
        :navigate navigate
        :total 4}]]]]))

(defmethod render-slide 3
  [{:keys [navigate slide skip] :as props}]
  (mf/html
   [:div.modal-container.onboarding.feature
    [:div.modal-left
     [:img {:src "images/on-handoff.gif" :border "0" :alt (tr "onboarding.slide.3.alt")}]]
    [:div.modal-right
     [:div.modal-title
      [:h2 {:data-test "slide-3-title"} (tr "onboarding.slide.3.title")]]
     [:div.modal-content
      [:p (tr "onboarding.slide.3.desc1")]
      [:p (tr "onboarding.slide.3.desc2")]]
     [:div.modal-navigation
      [:button.btn-secondary {:on-click skip
                              :data-test "slide-3-btn"} (tr "labels.start")]
      [:& rc/navigation-bullets
       {:slide slide
        :navigate navigate
        :total 4}]]]]))

(mf/defc onboarding-modal
  {::mf/register modal/components
   ::mf/register-as :onboarding}
  [props]
  (let [slide (mf/use-state :start)
        klass (mf/use-state "fadeInDown")

        navigate
        (mf/use-callback #(reset! slide %))

        skip
        (mf/use-callback
         (st/emitf (modal/hide)
                   (modal/show {:type :onboarding-newsletter-modal})
                   (du/mark-onboarding-as-viewed)))]

    (mf/use-layout-effect
     (mf/deps @slide)
     (fn []
       (when (not= :start @slide)
         (reset! klass "fadeIn"))
       (let [sem (tm/schedule 300 #(reset! klass nil))]
         (fn []
           (reset! klass nil)
           (tm/dispose! sem)))))

    [:div.modal-overlay
     [:div.animated {:class @klass}
      (case @slide
        :start      [:& onboarding-start {:next #(navigate :opensource)}]
        :opensource [:& onboarding-opensource {:next #(navigate 0)}]
        (render-slide
         (assoc props
                :slide @slide
                :navigate navigate
                :skip skip)))]]))
