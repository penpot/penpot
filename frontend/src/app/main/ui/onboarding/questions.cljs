;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.onboarding.questions
  "External form for onboarding questions."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.config :as cf]
   [app.main.data.modal :as modal]
   [app.main.data.users :as du]
   [app.main.store :as st]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.icons :as i]
   [app.util.i18n :as i18n :refer [tr]]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(mf/defc step-container
  [{:keys [form step on-next on-prev children class] :as props}]

  [:& fm/form {:form form :on-submit on-next :class (dm/str class " " (stl/css :form-wrapper))}
   [:div {:class (stl/css :paginator)} (str/ffmt "%/5" step)]

   children

   [:div {:class (stl/css :action-buttons)}

    (when on-prev
      [:button {:class (stl/css :prev-button)
                :on-click on-prev} (tr "questions.previous")])

    [:> fm/submit-button*
     {:label (if (< step 5) (tr "questions.next") (tr "questions.start"))
      :class (stl/css :next-button)}]]])

(s/def ::questions-form-step-1
  (s/keys :req-un [::planning
                   ::penpot-use]
          :opt-un [::planning-other]))

(defn- step-1-form-validator
  [errors data]
  (let [planning (-> (:planning data) (str/trim))
        planning-other (-> (:planning-other data) str/trim)]

    (cond-> errors
      (and (= planning-other "other") (= 0 (count planning-other)))
      (assoc :planning-other {:code "missing"})

      (= planning "")
      (assoc :planning {:code "missing"}))))

(mf/defc step-1
  [{:keys [on-next form] :as props}]
  (let [use-ops-randomized (mf/with-memo [] (shuffle [{:label (tr "questions.use-work") :value "use-work"}
                                                      {:label (tr "questions.use-education") :value "use-education"}
                                                      {:label (tr "questions.use-personal") :value "use-personal"}]))

        planning-ops (mf/with-memo [] (shuffle [{:label (tr "questions.select-option")
                                                 :value "" :key "questions-what-brings-you-here"
                                                 :disabled true}
                                                {:label (tr "questions.reasons.exploring")
                                                 :value "discover-more-about-penpot"
                                                 :key "discover-more-about-penpot"}
                                                {:label (tr "questions.reasons.fit")
                                                 :value "test-penpot-to-see-if-its-a-fit-for-team"
                                                 :key "test-penpot-to-see-if-its-a-fit-for-team"}
                                                {:label (tr "questions.reasons.alternative")
                                                 :value "alternative-to-figma"
                                                 :key "alternative-to-figma"}
                                                {:label (tr "questions.reasons.testing")
                                                 :value "try-out-before-using-penpot-on-premise"
                                                 :key "try-out-before-using-penpot-on-premise"}]))

        planning-ops-randomized (conj planning-ops {:label (tr "questions.other-short") :value "other"})

        planning (dm/get-in @form [:data :planning])]


    [:& step-container {:form form :step 1 :on-next on-next :class (stl/css :step-1)}
     [:img {:class (stl/css :header-image)
            :src "images/form/use-for-1.png"
            :alt (tr "questions.lets-get-started")}]
     [:h1 {:class (stl/css :modal-title)}
      (tr "questions.step1-title")]
     [:p {:class (stl/css :modal-text)}
      (tr "questions.step1-subtitle")]

     [:div {:class (stl/css :modal-question)}
      [:h3 {:class (stl/css :modal-subtitle)} (tr "questions.step1-question1")]
      [:& fm/radio-buttons {:options use-ops-randomized
                            :name :penpot-use
                            :class (stl/css :radio-btns)}]
      [:h3 {:class (stl/css :modal-subtitle)} (tr "questions.step1-question2")]
      [:& fm/select
       {:options planning-ops-randomized
        :select-class (stl/css :select-class)
        :default ""
        :name :planning
        :dropdown-class (stl/css :question-dropdown)}]

      (when (= planning "other")
        [:& fm/input {:name :planning-other
                      :class (stl/css :input-spacing)
                      :placeholder (tr "questions.other")
                      :label ""}])]]))

(s/def ::questions-form-step-2
  (s/keys :req-un [::experience-design-tool]
          :opt-un [::experience-design-tool-other]))

(defn- step-2-form-validator
  [errors data]
  (let [experience-design-tool (:experience-design-tool data)
        experience-design-tool-other (-> (:experience-design-tool-other data) str/trim)]
    (cond-> errors
      (and (= experience-design-tool "other") (= 0 (count experience-design-tool-other)))
      (assoc :experience-design-tool-other {:code "missing"}))))

(mf/defc step-2
  [{:keys [on-next on-prev form] :as props}]
  (let [design-tool-options (mf/with-memo [] (shuffle [{:label (tr "questions.figma")  :img-width "48px" :img-height "60px" :value "figma" :image "images/form/figma.png"}
                                                       {:label (tr "questions.sketch") :img-width "48px" :img-height "60px" :value "sketch" :image "images/form/sketch.png"}
                                                       {:label (tr "questions.adobe-xd") :img-width "48px" :img-height "60px" :value "adobe-xd" :image "images/form/adobe-xd.png"}
                                                       {:label (tr "questions.canva") :img-width "48px" :img-height "60px" :value "canva" :image "images/form/canva.png"}
                                                       {:label (tr "questions.invision")  :img-width "48px" :img-height "60px" :value "invision" :image "images/form/invision.png"}]))

        design-tool-options-randomized (conj design-tool-options {:label (tr "questions.other-short")  :value "other" :icon i/curve})

        experience-design-tool (dm/get-in @form [:clean-data :experience-design-tool])
        on-design-tool-change
        (fn [_ _]
          (let [experience-design-tool (dm/get-in @form [:clean-data :experience-design-tool])]
            (when (not= experience-design-tool "other")
              (do
                (swap! form d/dissoc-in [:data :experience-design-tool-other])
                (swap! form d/dissoc-in [:errors :experience-design-tool-other])))))]

    [:& step-container {:form form :step 2 :on-next on-next :on-prev on-prev :class (stl/css :step-2)}
     [:h1 {:class (stl/css :modal-title)}
      (tr "question.design-tool-more-used")]
     [:div {:class (stl/css :radio-wrapper)}
      [:& fm/image-radio-buttons {:options design-tool-options-randomized
                                  :img-width "48px"
                                  :img-height "60px"
                                  :name :experience-design-tool
                                  :image true
                                  :class (stl/css :image-radio)
                                  :on-change on-design-tool-change}]

      (when (= experience-design-tool "other")
        [:& fm/input {:name :experience-design-tool-other
                      :class (stl/css :input-spacing)
                      :placeholder (tr "questions.other")
                      :label ""}])]]))

(s/def ::questions-form-step-3
  (s/keys :req-un [::team-size ::role ::responsability]
          :opt-un [::role-other ::responsability-other]))

(defn- step-3-form-validator
  [errors data]
  (let [role (:role data)
        role-other (-> (:role-other data) str/trim)

        responsability (:responsability data)
        responsability-other (-> (:responsability-other data) str/trim)]
    (cond-> errors
      (and (= role "other") (= 0 (count role-other)))
      (assoc :role-other {:code "missing"})

      (and (= responsability "other") (= 0 (count responsability-other)))
      (assoc :responsability-other {:code "missing"}))))

(mf/defc step-3
  [{:keys [on-next on-prev form] :as props}]
  (let [role-ops (mf/with-memo [] (shuffle [{:label (tr "questions.select-option") :value "" :key "role" :disabled true}
                                            {:label (tr "questions.work-type.ux") :value "designer" :key "designer"}
                                            {:label (tr "questions.work-type.dev") :value "developer"  :key "developer"}
                                            {:label (tr "questions.work-type.student") :value "student-teacher" :key "student"}
                                            {:label (tr "questions.work-type.graphic") :value "graphic-design" :key "design"}
                                            {:label (tr "questions.work-type.marketing") :value "marketing" :key "marketing"}
                                            {:label (tr "questions.work-type.product") :value "manager" :key "manager"}]))
        role-ops-randomized (conj role-ops {:label (tr "questions.other-short") :value "other"})

        responsability-options (mf/with-memo [] (shuffle [{:label (tr "questions.select-option") :value "" :key "responsability" :disabled true}
                                                          {:label (tr "questions.role.team-leader") :value "team-leader"}
                                                          {:label (tr "questions.role.team-member") :value "team-member"}
                                                          {:label (tr "questions.role.freelancer") :value "freelancer"}
                                                          {:label (tr "questions.role.founder") :value "ceo-founder"}
                                                          {:label (tr "questions.role.director") :value "director"}
                                                          {:label (tr "questions.student-teacher") :value "student-teacher"}]))

        responsability-options-randomized (conj responsability-options {:label (tr "questions.other-short") :value "other"})


        role (dm/get-in @form [:data :role])

        responsability (dm/get-in @form [:data :responsability])]

    [:& step-container {:form form :step 3 :on-next on-next :on-prev on-prev :class (stl/css :step-3)}
     [:h1 {:class (stl/css :modal-title)} (tr "questions.step3-title")]
     [:div {:class (stl/css :modal-question)}
      [:h3 {:class (stl/css :modal-subtitle)} (tr "questions.step3.question1")]
      [:& fm/select {:options role-ops-randomized
                     :select-class (stl/css :select-class)
                     :default ""
                     :name :role}]

      (when (= role "other")
        [:& fm/input {:name :role-other
                      :class (stl/css :input-spacing)
                      :placeholder (tr "questions.other")
                      :label ""}])]

     [:div {:class (stl/css :modal-question)}
      [:h3 {:class (stl/css :modal-subtitle)} (tr "questions.step3.question2")]
      [:& fm/select {:options responsability-options-randomized
                     :select-class (stl/css :select-class)
                     :default ""
                     :name :responsability}]

      (when (= responsability "other")
        [:& fm/input {:name :responsability-other
                      :class (stl/css :input-spacing)
                      :placeholder (tr "questions.other")
                      :label ""}])]

     [:div {:class (stl/css :modal-question)}
      [:h3 {:class (stl/css :modal-subtitle)} (tr "questions.company-size")]
      [:& fm/select {:options [{:label (tr "questions.select-option") :value "" :key "team-size" :disabled true}
                               {:label (tr "questions.more-than-50") :value "more-than-50" :key "more-than-50"}
                               {:label (tr "questions.31-50") :value "31-50"  :key "31-50"}
                               {:label (tr "questions.11-30") :value "11-30" :key "11-30"}
                               {:label (tr "questions.2-10") :value "2-10" :key "2-10"}
                               {:label (tr "questions.freelancer") :value "freelancer" :key "freelancer"}
                               {:label (tr "questions.personal-project") :value "personal-project" :key "personal-project"}]
                     :default ""
                     :select-class (stl/css :select-class)
                     :name :team-size}]]]))

(s/def ::questions-form-step-4
  (s/keys :req-un [::start]
          :opt-un [::start-other]))

(defn- step-4-form-validator
  [errors data]
  (let [start (:start data)
        start-other (-> (:start-other data) str/trim)]
    (cond-> errors
      (and (= start "other") (= 0 (count start-other)))
      (assoc :start-other {:code "missing"}))))

(mf/defc step-4
  [{:keys [on-next on-prev form] :as props}]
  (let [start-options (mf/with-memo [] (shuffle [{:label (tr "questions.starting-ui") :value "ui" :image "images/form/Design.png"}
                                                 {:label (tr "questions.starting-wireframing") :value "wireframing" :image "images/form/templates.png"}
                                                 {:label (tr "questions.starting-prototyping") :value "prototyping" :image "images/form/Prototype.png"}
                                                 {:label (tr "questions.starting-ds") :value "ds" :image "images/form/components.png"}
                                                 {:label (tr "questions.starting-code") :value "code" :image "images/form/design-and-dev.png"}]))

        start-options-randomized (conj start-options {:label (tr "questions.other-short") :value "other" :icon i/curve})


        start (dm/get-in @form [:data :start])

        on-start-change
        (fn [_ _]
          (let [start (dm/get-in @form [:clean-data :start])]
            (when (not= start "other")
              (do
                (swap! form d/dissoc-in [:data :start-other])
                (swap! form d/dissoc-in [:errors :start-other])))))]

    [:& step-container {:form form :step 4 :on-next on-next :on-prev on-prev :class (stl/css :step-4)}
     [:h1 {:class (stl/css :modal-title)} (tr "questions.step4-title")]
     [:div {:class (stl/css :radio-wrapper)}
      [:& fm/image-radio-buttons {:options start-options-randomized
                                  :img-width "159px"
                                  :img-height "120px"
                                  :class (stl/css :image-radio)
                                  :name :start
                                  :on-change on-start-change}]

      (when (= start "other")
        [:& fm/input {:name :start-other
                      :class (stl/css :input-spacing)
                      :label ""
                      :placeholder (tr "questions.other")
                      :disabled (not= start "other")}])]]))

(s/def ::questions-form-step-5
  (s/keys :req-un [::knowledge]
          :opt-un [::knowledge-other]))

(defn- step-5-form-validator
  [errors data]
  (let [knowledge (:knowledge data)
        knowledge-other (-> (:knowledge-other data) str/trim)]
    (cond-> errors
      (and (= knowledge "other") (= 0 (count knowledge-other)))
      (assoc :knowledge-other {:code "missing"}))))

(mf/defc step-5
  [{:keys [on-next on-prev form] :as props}]
  (let [knowledge-options (mf/with-memo [] (shuffle [{:label (tr "questions.knowledge.youtube") :value "Youtube"}
                                                     {:label (tr "questions.knowledge.event") :value "event"}
                                                     {:label (tr "questions.knowledge.search") :value "search"}
                                                     {:label (tr "questions.knowledge.social") :value "social"}
                                                     {:label (tr "questions.knowledge.article") :value "article"}]))
        knowledge-options-randomized (conj knowledge-options {:label (tr "questions.other-short") :value "other"})

        knowledge (dm/get-in @form [:data :knowledge])
        on-knowledge-change
        (fn [_ _]
          (let [experience-design-tool (dm/get-in @form [:clean-data :experience-design-tool])]
            (when (not= experience-design-tool "other")
              (do
                (swap! form d/dissoc-in [:data :knowledge-other])
                (swap! form d/dissoc-in [:errors :knowledge-other])))))]

    [:& step-container {:form form :step 5 :on-next on-next :on-prev on-prev :class (stl/css :step-5)}
     [:h1 {:class (stl/css :modal-title)} (tr "questions.step5-title")]
     [:div {:class (stl/css :radio-wrapper)}
      [:& fm/radio-buttons {:options knowledge-options-randomized
                            :class (stl/css :radio-btns)
                            :name :knowledge
                            :on-change on-knowledge-change}]
      (when (= knowledge "other")
        [:& fm/input {:name :knowledge-other
                      :class (stl/css :input-spacing)
                      :label ""
                      :placeholder (tr "questions.other")
                      :disabled (not= knowledge "other")}])]]))

(mf/defc questions-modal
  {::mf/register modal/components
   ::mf/register-as :onboarding-questions}
  []
  (let [container   (mf/use-ref)
        step        (mf/use-state 1)
        clean-data  (mf/use-state {})

        ;; Forms are initialized here because we can go back and forth between the steps
        ;; and we want to keep the filled info
        step-1-form (fm/use-form
                     :initial {}
                     :validators [step-1-form-validator]
                     :spec ::questions-form-step-1)

        step-2-form (fm/use-form
                     :initial {}
                     :validators [step-2-form-validator]
                     :spec ::questions-form-step-2)

        step-3-form (fm/use-form
                     :initial {}
                     :validators [step-3-form-validator]
                     :spec ::questions-form-step-3)

        step-4-form (fm/use-form
                     :initial {}
                     :validators [step-4-form-validator]
                     :spec ::questions-form-step-4)

        step-5-form (fm/use-form
                     :initial {}
                     :validators [step-5-form-validator]
                     :spec ::questions-form-step-5)

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
             (st/emit! (du/mark-questions-as-answered questionnaire))

             (cond
               (contains? cf/flags :onboarding-newsletter)
               (modal/show! {:type :onboarding-newsletter})

               (contains? cf/flags :onboarding-team)
               (modal/show! {:type :onboarding-team})

               :else
               (modal/hide!)))))
        onboarding-a-b-test? (cf/external-feature-flag "signup-background" "test")]

    [:div {:class (stl/css-case :modal-overlay true
                                :onboarding-a-b-test onboarding-a-b-test?)}
     [:div {:class (stl/css :modal-container)
            :ref container}
      (case @step
        1 [:& step-1 {:on-next on-next :on-prev on-prev :form step-1-form}]
        2 [:& step-2 {:on-next on-next :on-prev on-prev :form step-2-form}]
        3 [:& step-3 {:on-next on-next :on-prev on-prev :form step-3-form}]
        4 [:& step-4 {:on-next on-next :on-prev on-prev :form step-4-form}]
        5 [:& step-5 {:on-next on-submit :on-prev on-prev :form step-5-form}])]]))
