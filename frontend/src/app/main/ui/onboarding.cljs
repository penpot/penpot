;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.onboarding
  (:require
   [app.common.spec :as us]
   [app.main.data.dashboard :as dd]
   [app.main.data.messages :as dm]
   [app.main.data.modal :as modal]
   [app.main.data.users :as du]
   [app.main.store :as st]
   [app.main.ui.components.forms :as fm :refer [input submit-button form]]
   [app.util.dom :as dom]
   [app.util.router :as rt]
   [app.util.timers :as tm]
   [cljs.spec.alpha :as s]
   [rumext.alpha :as mf]))


(mf/defc navigation-bullets
  [{:keys [slide navigate total]}]
  [:ul.step-dots
   (for [i (range total)]
     [:li {:class (dom/classnames :current (= slide i))
           :on-click #(navigate i)}])])

(mf/defc onboarding-start
  [{:keys [next] :as props}]
  [:div.modal-container.onboarding
   [:div.modal-left
    [:img {:src "images/pot.png" :border "0" :alt "Penpot"}]]
   [:div.modal-right
    [:div.modal-title
     [:h2 "Welcome to Penpot!"]]
    [:span.release "Alpha version 1.0"]
    [:div.modal-content
     [:p "We are very happy to introduce you to the very first Alpha 1.0 release."]
     [:p "Penpot is still at development stage and there will be constant updates. We hope you enjoy the first stable version."]]
    [:div.modal-navigation
     [:button.btn-secondary {:on-click next} "Continue"]]]
   [:img.deco {:src "images/deco-left.png" :border "0"}]
   [:img.deco.right {:src "images/deco-right.png" :border "0"}]])

(mf/defc onboarding-opensource
  [{:keys [next] :as props}]
  [:div.modal-container.onboarding.black
   [:div.modal-left
    [:img {:src "images/open-source.svg" :border "0" :alt "Open Source"}]]
   [:div.modal-right
    [:div.modal-title
     [:h2 "Open Source Contributor?"]]
    [:div.modal-content
     [:p "Penpot is Open Source, made by and for the community. If you want to collaborate, you are more than welcome!"]
     [:p "You can access the " [:a {:href "https://github.com/penpot" :target "_blank"} "project on github"] " and follow the contribution instructions :)"]]
    [:div.modal-navigation
     [:button.btn-secondary {:on-click next} "Continue"]]]])

(defmulti render-slide :slide)

(defmethod render-slide 0
  [{:keys [navigate skip slide] :as props}]
  (mf/html
   [:div.modal-container.onboarding.feature
    [:div.modal-left
     [:img {:src "images/on-design.gif" :border "0" :alt "Create designs"}]]
    [:div.modal-right
     [:div.modal-title
      [:h2 "Design libraries, styles and components"]]
     [:div.modal-content
      [:p "Create beautiful user interfaces in collaboration with all team members."]
      [:p "Maintain consistency at scale with components, libraries and design systems."]]
     [:div.modal-navigation
      [:button.btn-secondary {:on-click #(navigate 1)} "Continue"]
      [:span.skip {:on-click skip} "Skip"]
      [:& navigation-bullets
       {:slide slide
        :navigate navigate
        :total 4}]]]]))

(defmethod render-slide 1
  [{:keys [navigate slide skip] :as props}]
  (mf/html
   [:div.modal-container.onboarding.feature
    [:div.modal-left
     [:img {:src "images/on-proto.gif" :border "0" :alt "Interactive prototypes"}]]
    [:div.modal-right
     [:div.modal-title
      [:h2 "Bring your designs to life with interactions"]]
     [:div.modal-content
      [:p "Create rich interactions to mimic the product behaviour."]
      [:p "Share to stakeholders, present proposals to your team and start user testing with your designs, all in one place."]]
     [:div.modal-navigation
      [:button.btn-secondary {:on-click #(navigate 2)} "Continue"]
      [:span.skip {:on-click skip} "Skip"]
      [:& navigation-bullets
       {:slide slide
        :navigate navigate
        :total 4}]]]]))

(defmethod render-slide 2
  [{:keys [navigate slide skip] :as props}]
  (mf/html
   [:div.modal-container.onboarding.feature
    [:div.modal-left
     [:img {:src "images/on-feed.gif" :border "0" :alt "Get feedback"}]]
    [:div.modal-right
     [:div.modal-title
      [:h2 "Get feedback, present and share your work"]]
     [:div.modal-content
      [:p "All team members working simultaneously with real time design multiplayer and centralised comments, ideas and feedback right over the designs."]]
     [:div.modal-navigation
      [:button.btn-secondary {:on-click #(navigate 3)} "Continue"]
      [:span.skip {:on-click skip} "Skip"]
      [:& navigation-bullets
       {:slide slide
        :navigate navigate
        :total 4}]]]]))

(defmethod render-slide 3
  [{:keys [navigate slide skip] :as props}]
  (mf/html
   [:div.modal-container.onboarding.feature
    [:div.modal-left
     [:img {:src "images/on-handoff.gif" :border "0" :alt "Handoff and lowcode"}]]
    [:div.modal-right
     [:div.modal-title
      [:h2 "One shared source of truth"]]
     [:div.modal-content
      [:p "Sync the design and code of all your components and styles and get code snippets."]
      [:p "Get and provide code specifications like markup (SVG, HTML) or styles (CSS, Less, Stylus…)."]]
     [:div.modal-navigation
      [:button.btn-secondary {:on-click skip} "Start"]
      [:& navigation-bullets
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
                   (modal/show {:type :onboarding-team})
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

(s/def ::name ::us/not-empty-string)
(s/def ::team-form
  (s/keys :req-un [::name]))

(defn- on-success
  [form response]
  (st/emit! (modal/hide)
            (rt/nav :dashboard-projects {:team-id (:id response)})))

(defn- on-error
  [form response]
  (st/emit! (dm/error "Error on creating team.")))

(defn- on-submit
  [form event]
  (let [mdata  {:on-success (partial on-success form)
                :on-error   (partial on-error form)}
        params {:name (get-in @form [:clean-data :name])}]
    (st/emit! (dd/create-team (with-meta params mdata)))))

(mf/defc onboarding-team-modal
  {::mf/register modal/components
   ::mf/register-as :onboarding-team}
  [props]
  (let [close (mf/use-fn (st/emitf (modal/hide)))
        form  (fm/use-form :spec ::team-form
                           :initial {})

        on-submit
        (mf/use-callback (partial on-submit form))]
    [:div.modal-overlay
     [:div.modal-container.onboarding.final.animated.fadeInUp
      [:div.modal-left
       [:img {:src "images/onboarding-team.jpg" :border "0" :alt "Create a team"}]
       [:h2 "Create a team"]
       [:p "Are you working with someone? Create a team to work together on projects and share design assets."]

       [:& fm/form {:form form
                    :on-submit on-submit}
        [:& fm/input {:type "text"
                   :name :name
                   :label "Enter new team name"}]
        [:& fm/submit-button
         {:label "Create team"}]]]
      [:div.modal-right
       [:img {:src "images/onboarding-start.jpg" :border "0" :alt "Start designing"}]
       [:h2 "Start designing"]
       [:p "Jump right away into Penpot and start designing by your own. You will still have the chance to create teams later."]
       [:button.btn-primary.btn-large {:on-click close} "Start right away"]]


      [:img.deco {:src "images/deco-left.png" :border "0"}]
      [:img.deco.right {:src "images/deco-right.png" :border "0"}]]]))

