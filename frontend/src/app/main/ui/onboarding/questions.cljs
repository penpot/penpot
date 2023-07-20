;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.onboarding.questions
  "External form for onboarding questions."
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.main.data.users :as du]
   [app.main.store :as st]
   [app.main.ui.components.forms :as fm]
   [app.util.i18n :as i18n :refer [tr]]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(mf/defc step-container
  [{:keys [form step on-next on-prev children] :as props}]
  [:& fm/form {:form form :on-submit on-next}
   [:div.step-header
    [:div.step-number (str/ffmt "%/4" step)]]

   children
   [:div.buttons
    [:div.step-next
     [:> fm/submit-button*
      {:label (if (< step 4) (tr "questions.next") (tr "questions.start"))
       :class "step-next"}]]

    (when on-prev
      [:div.step-prev
       [:button {:on-click on-prev} (tr "questions.previous")]])]])

(s/def ::questions-form-step-1
  (s/keys :req-un [::planning]))

(mf/defc step-1
  [{:keys [on-next form] :as props}]
  [:& step-container {:form form :step 1 :on-next on-next}
   [:img.header-image {:src "images/form/use-for-1.png" :alt (tr "questions.lets-get-started")}]
   [:h1 (tr "questions.lets-get-started")]
   [:p.intro (tr "questions.your-feedback-will-help-us")]
   [:h3 (tr "questions.questions-how-are-you-planning-to-use-penpot")]
   [:& fm/select {:options [{:label (tr "questions.select-option") :value "" :key "questions-how-are-you-planning-to-use-penpot" :disabled true}
                            {:label (tr "questions.discover-more-about-penpot") :value "discover-more-about-penpot" :key "discover-more-about-penpot"}
                            {:label (tr "questions.test-penpot-to-see-if-its-a-fit-for-team") :value "test-penpot-to-see-if-its-a-fit-for-team"  :key "test-penpot-to-see-if-its-a-fit-for-team"}
                            {:label (tr "questions.start-to-work-on-my-project") :value "start-to-work-on-my-project" :key "start-to-work-on-my-project"}
                            {:label (tr "questions.get-the-code-from-my-team-project") :value "get-the-code-from-my-team-project" :key "get-the-code-from-my-team-project"}
                            {:label (tr "questions.leave-feedback-for-my-team-project") :value "leave-feedback-for-my-team-project" :key "leave-feedback-for-my-team-project"}
                            {:label (tr "questions.work-in-concept-ideas") :value "work-in-concept-ideas" :key "work-in-concept-ideas"}
                            {:label (tr "questions.try-out-before-using-penpot-on-premise") :value "try-out-before-using-penpot-on-premise" :key "try-out-before-using-penpot-on-premise"}]
                  :default ""
                  :name :planning}]])

(s/def ::questions-form-step-2
  (s/keys :req-un [::experience-branding-illustrations-marketing-pieces ::experience-interface-design-visual-assets-design-systems ::experience-interface-wireframes-user-journeys-flows-navigation-trees]))

(mf/defc step-2
  [{:keys [on-next on-prev form] :as props}]
  [:& step-container {:form form :step 2 :on-next on-next :on-prev on-prev}
   [:h3 (tr "questions.describe-your-experience-working-on")]

   [:div.section (tr "branding-illustrations-marketing-pieces")]
   [:& fm/radio-buttons {:options [{:label (tr "questions.none") :value "none"}
                                   {:label (tr "questions.some") :value "some"}
                                   {:label (tr "questions.a-lot") :value "a-lot"}]
                         :name :experience-branding-illustrations-marketing-pieces}]

   [:div.section (tr "questions.interface-design-visual-assets-design-systems")]
   [:& fm/radio-buttons {:options [{:label (tr "questions.none") :value "none"}
                                   {:label (tr "questions.some") :value "some"}
                                   {:label (tr "questions.a-lot") :value "a-lot"}]
                         :name :experience-interface-design-visual-assets-design-systems}]

   [:div.section (tr "questions.wireframes-user-journeys-flows-navigation-trees")]
   [:& fm/radio-buttons {:options [{:label (tr "questions.none") :value "none"}
                                   {:label (tr "questions.some") :value "some"}
                                   {:label (tr "questions.a-lot") :value "a-lot"}]
                         :name :experience-interface-wireframes-user-journeys-flows-navigation-trees}]])

(s/def ::questions-form-step-3
  (s/keys :req-un [::experience-design-tool]
    :opt-un[::experience-design-tool-other]))

(defn- step-3-form-validator
  [errors data]
  (let [experience-design-tool (:experience-design-tool data)
        experience-design-tool-other (-> (:experience-design-tool-other data) str/trim)]
    (cond-> errors
      (and (= experience-design-tool "other") (= 0 (count experience-design-tool-other)))
      (assoc :experience-design-tool-other {:code "missing"}))))

(mf/defc step-3
  [{:keys [on-next on-prev form] :as props}]
  (let [experience-design-tool (dm/get-in @form [:clean-data :experience-design-tool])
        on-design-tool-change
        (fn [_ _]
          (let [experience-design-tool (dm/get-in @form [:clean-data :experience-design-tool])]
            (when (not= experience-design-tool "other")
              (do
                (swap! form d/dissoc-in [:data :experience-design-tool-other])
                (swap! form d/dissoc-in [:errors :experience-design-tool-other])))))]

    [:& step-container {:form form :step 3 :on-next on-next :on-prev on-prev}
     [:h3 (tr "question.design-tool-more-experienced-with")]
     [:& fm/radio-buttons {:options [{:label (tr "questions.figma") :value "figma" :image "images/form/figma.png"}
                                     {:label (tr "questions.sketch") :value "sketch" :image "images/form/sketch.png"}
                                     {:label (tr "questions.adobe-xd") :value "adobe-xd" :image "images/form/adobe-xd.png"}
                                     {:label (tr "questions.canva") :value "canva" :image "images/form/canva.png"}
                                     {:label (tr "questions.invision") :value "invision" :image "images/form/invision.png"}
                                     {:label (tr "questions.never-used-a-tool") :value "never-used-a-tool" :image "images/form/never-used.png"}
                                     {:label (tr "questions.other") :value "other"}]
                           :name :experience-design-tool
                           :on-change on-design-tool-change}]
     [:div.other
      [:label (tr "questions.other")]
      [:& fm/input {:name :experience-design-tool-other :label (tr "questions.other") :disabled (not= experience-design-tool "other")}]]]))

(s/def ::questions-form-step-4
  (s/keys :req-un [::team-size ::role]
    :opt-un [::role-other]))

(defn- step-4-form-validator
  [errors data]
  (let [role (:role data)
        role-other (-> (:role-other data) str/trim)]
    (cond-> errors
      (and (= role "other") (= 0 (count role-other)))
      (assoc :role-other {:code "missing"}))))

(mf/defc step-4
  [{:keys [on-next on-prev form] :as props}]
  (let [role (dm/get-in @form [:data :role])
        on-role-change
        (fn [_ _]
          (let [experience-design-tool (dm/get-in @form [:clean-data :experience-design-tool])]
            (when (not= experience-design-tool "other")
              (do
                (swap! form d/dissoc-in [:data :role-other])
                (swap! form d/dissoc-in [:errors :role-other])))))]

    [:& step-container {:form form :step 4 :on-next on-next :on-prev on-prev}
     [:h3 (tr "questions.role")]
     [:& fm/radio-buttons {:options [{:label (tr "questions.designer") :value "designer"}
                                     {:label (tr "questions.developer") :value "developer"}
                                     {:label (tr "questions.manager") :value "manager"}
                                     {:label (tr "questions.founder") :value "founder"}
                                     {:label (tr "questions.marketing") :value "marketing"}
                                     {:label (tr "questions.student-teacher") :value "student-teacher"}
                                     {:label (tr "questions.other") :value "other"}]
                           :name :role
                           :on-change on-role-change}]
     [:div.other
      [:label (tr "questions.other")]
      [:& fm/input {:name :role-other :label (tr "questions.other") :disabled (not= role "other")}]]

     [:h3 (tr "questions.team-size")]
     [:& fm/select {:options [{:label (tr "questions.select-option") :value "" :key "team-size" :disabled true}
                              {:label (tr "questions.more-than-50") :value "more-than-50" :key "more-than-50"}
                              {:label (tr "questions.31-50") :value "31-50"  :key "31-50"}
                              {:label (tr "questions.11-30") :value "11-30" :key "11-30"}
                              {:label (tr "questions.2-10") :value "2-10" :key "2-10"}
                              {:label (tr "questions.freelancer") :value "freelancer" :key "freelancer"}
                              {:label (tr "questions.personal-project") :value "personal-project" :key "personal-project"}]
                    :default ""
                    :name :team-size}]]))

(mf/defc questions
  [{:keys []}]
  (let [container   (mf/use-ref)
        step        (mf/use-state 1)
        clean-data  (mf/use-state {})

        ;; Forms are initialized here because we can go back and forth between the steps
        ;; and we want to keep the filled info
        step-1-form (fm/use-form
                      :initial {}
                      :spec ::questions-form-step-1)
        step-2-form (fm/use-form
                      :initial {}
                      :spec ::questions-form-step-2)
        step-3-form (fm/use-form
                      :initial {}
                      :validators [step-3-form-validator]
                      :spec ::questions-form-step-3)

        step-4-form (fm/use-form
                      :initial {}
                      :validators [step-4-form-validator]
                      :spec ::questions-form-step-4)

        on-next
        (mf/use-fn
          (fn [form]
            (swap! step inc)
            (swap! clean-data merge (:clean-data @form))))

        on-prev
        (mf/use-fn
          (fn []
            (swap! step dec)))

        on-submit
        (mf/use-fn
          (mf/deps @clean-data)
          (fn [form]
            (let [questionnaire (merge @clean-data (:clean-data @form))]
              (reset! clean-data questionnaire)
              (st/emit! (du/mark-questions-as-answered questionnaire)))))]

    [:div.modal-wrapper.questions-form
     [:div.modal-overlay
      [:div.modal-container.onboarding.onboarding-v2 {:ref container}
       [:img.deco.left {:src "images/deco-left.png" :border 0}]
       [:img.deco.right {:src "images/deco-right.png" :border 0}]
       [:div.signup-questions
        (case @step
          1 [:& step-1 {:on-next on-next :on-prev on-prev :form step-1-form}]
          2 [:& step-2 {:on-next on-next :on-prev on-prev :form step-2-form}]
          3 [:& step-3 {:on-next on-next :on-prev on-prev :form step-3-form}]
          4 [:& step-4 {:on-next on-submit :on-prev on-prev :form step-4-form}])]]]]))
