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
   [app.common.schema :as sm]
   [app.main.data.event :as-alias ev]
   [app.main.data.profile :as du]
   [app.main.store :as st]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.icons :as deprecated-icon]
   [app.util.i18n :as i18n :refer [tr]]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]))

(mf/defc step-container
  {::mf/props :obj}
  [{:keys [form step on-next on-prev children class label]}]

  (let [on-next*
        (mf/use-fn
         (mf/deps on-next step label)
         (fn [form event]
           (let [params (-> (:clean-data @form)
                            (assoc :label label)
                            (assoc :step step)
                            (assoc ::ev/name "onboarding-step"))]
             (st/emit! (ptk/data-event ::ev/event params))
             (on-next form event))))]

    [:& fm/form {:form form
                 :on-submit on-next*
                 :class (dm/str class " " (stl/css :form-wrapper))}

     children

     [:div {:class (stl/css :action-buttons)}

      (when (some? on-prev)
        [:button {:class (stl/css :prev-button)
                  :on-click on-prev}
         (tr "labels.previous")])

      [:> fm/submit-button*
       {:label (if (< step 4)
                 (tr "labels.next")
                 (tr "labels.start"))
        :class (stl/css :next-button)}]]]))

(def ^:private schema:questions-form-1
  [:and

   [:map {:title "QuestionsFormStep1"}
    [:expected-use [:enum "work" "education" "personal"]]
    [:role
     [:enum "ux" "developer" "student-teacher" "designer" "marketing" "manager" "other"]]
    [:role-other {:optional true} [::sm/text {:max 512}]]]

   [:fn {:error/field :role-other}
    (fn [{:keys [role role-other]}]
      (or (not= role "other")
          (and (= role "other")
               (not (str/blank? role-other)))))]])

(mf/defc step-1
  {::mf/props :obj}
  [{:keys [on-next form show-step-3]}]
  (let [use-options
        (mf/with-memo []
          (shuffle [{:label (tr "onboarding.questions.use.work") :value "work"}
                    {:label (tr "onboarding.questions.use.education") :value "education"}
                    {:label (tr "onboarding.questions.use.personal") :value "personal"}]))

        role-options
        (mf/with-memo []
          (-> (shuffle [{:label (tr "labels.select-option") :value "" :key "role" :disabled true}
                        {:label (tr "labels.product-design") :value "ux" :key "ux"}
                        {:label (tr "labels.developer") :value "developer"  :key "developer"}
                        {:label (tr "labels.student-teacher") :value "student-teacher" :key "student"}
                        {:label (tr "labels.graphic-design") :value "designer" :key "design"}
                        {:label (tr "labels.marketing") :value "marketing" :key "marketing"}
                        {:label (tr "labels.product-management") :value "manager" :key "manager"}])
              (conj {:label (tr "labels.other-short") :value "other"})))


        current-role
        (dm/get-in @form [:data :role])]


    [:& step-container {:form form
                        :step 1
                        :label "questions:about-you"
                        :on-next on-next
                        :class (stl/css :step-1)}

     [:div {:class (stl/css :paginator)} (str/ffmt "1/%" (if @show-step-3 4 3))]

     [:img {:class (stl/css :header-image)
            :src "images/form/use-for-1.png"
            :alt (tr "onboarding.questions.lets-get-started")}]
     [:h1 {:class (stl/css :modal-title)}
      (tr "onboarding.questions.step1.title")]
     [:p {:class (stl/css :modal-text)}
      (tr "onboarding.questions.step1.subtitle")]

     [:div {:class (stl/css :modal-question)}
      [:h3 {:class (stl/css :modal-subtitle)}
       (tr "onboarding.questions.step1.question1")]

      [:& fm/radio-buttons {:options use-options
                            :name :expected-use
                            :class (stl/css :radio-btns)}]

      [:h3 {:class (stl/css :modal-subtitle)} (tr "onboarding.questions.step3.question1")]
      [:& fm/select {:options role-options
                     :select-class (stl/css :select-class)
                     :default ""
                     :name :role}]

      (when (= current-role "other")
        [:& fm/input {:name :role-other
                      :class (stl/css :input-spacing)
                      :placeholder (tr "labels.other")
                      :show-error false
                      :label ""}])]]))

(def ^:private schema:questions-form-2
  [:and
   [:map {:title "QuestionsFormStep2"}
    [:experience-design-tool
     [:enum "figma" "sketch" "adobe-xd" "canva" "invision" "other"]]
    [:experience-design-tool-other {:optional true}
     [::sm/text {:max 512}]]]

   [:fn {:error/field :experience-design-tool-other}
    (fn [data]
      (let [experience       (:experience-design-tool data)
            experience-other (:experience-design-tool-other data)]
        (or (not= experience "other")
            (and (= experience "other")
                 (not (str/blank? experience-other))))))]])

(mf/defc step-2
  {::mf/props :obj}
  [{:keys [on-next on-prev form show-step-3]}]
  (let [design-tool-options
        (mf/with-memo []
          (-> (shuffle [{:label (tr "labels.figma")  :img-width "48px" :img-height "60px"
                         :value "figma" :image "images/form/figma.png"}
                        {:label (tr "labels.sketch") :img-width "48px" :img-height "60px"
                         :value "sketch" :image "images/form/sketch.png"}
                        {:label (tr "labels.adobe-xd") :img-width "48px" :img-height "60px"
                         :value "adobe-xd" :image "images/form/adobe-xd.png"}
                        {:label (tr "labels.canva") :img-width "48px" :img-height "60px"
                         :value "canva" :image "images/form/canva.png"}
                        {:label (tr "labels.invision")  :img-width "48px" :img-height "60px"
                         :value "invision" :image "images/form/invision.png"}])
              (conj {:label (tr "labels.other-short")  :value "other" :icon deprecated-icon/curve})))

        current-experience
        (dm/get-in @form [:data :experience-design-tool])

        on-design-tool-change
        (mf/use-fn
         (mf/deps current-experience)
         (fn []
           (when (not= current-experience "other")
             (swap! form d/dissoc-in [:data :experience-design-tool-other])
             (swap! form d/dissoc-in [:errors :experience-design-tool-other]))))]

    [:& step-container {:form form
                        :step 2
                        :label "questions:experience-design-tool"
                        :on-next on-next
                        :on-prev on-prev
                        :class (stl/css :step-2)}

     [:div {:class (stl/css :paginator)} (str/ffmt "2/%" (if @show-step-3 4 3))]


     [:h1 {:class (stl/css :modal-title)}
      (tr "onboarding.questions.step2.title")]
     [:div {:class (stl/css :radio-wrapper)}
      [:& fm/image-radio-buttons {:options design-tool-options
                                  :img-width "48px"
                                  :img-height "60px"
                                  :name :experience-design-tool
                                  :image true
                                  :class (stl/css :image-radio)
                                  :on-change on-design-tool-change}]

      (when (= current-experience "other")
        [:& fm/input {:name :experience-design-tool-other
                      :class (stl/css :input-spacing)
                      :placeholder (tr "labels.other")
                      :show-error false
                      :label ""}])]]))


(def ^:private schema:questions-form-3
  [:and
   [:map {:title "QuestionsFormStep3"}
    [:team-size
     [:enum "more-than-50" "31-50" "11-30" "2-10" "freelancer" "personal-project"]]

    [:planning ::sm/text]

    [:planning-other {:optional true}
     [::sm/text {:max 512}]]]

   [:fn {:error/field :planning-other}
    (fn [{:keys [planning planning-other]}]
      (or (not= planning "other")
          (and (= planning "other")
               (not (str/blank? planning-other)))))]])

(mf/defc step-3
  {::mf/props :obj}
  [{:keys [on-next on-prev form show-step-3]}]
  (let [team-size-options
        (mf/with-memo []
          [{:label (tr "labels.select-option") :value "" :key "team-size" :disabled true}
           {:label (tr "onboarding.questions.team-size.more-than-50") :value "more-than-50" :key "more-than-50"}
           {:label (tr "onboarding.questions.team-size.31-50") :value "31-50"  :key "31-50"}
           {:label (tr "onboarding.questions.team-size.11-30") :value "11-30" :key "11-30"}
           {:label (tr "onboarding.questions.team-size.2-10") :value "2-10" :key "2-10"}
           {:label (tr "onboarding.questions.team-size.freelancer") :value "freelancer" :key "freelancer"}
           {:label (tr "onboarding.questions.team-size.personal-project") :value "personal-project" :key "personal-project"}])

        planning-options
        (mf/with-memo []
          (-> (shuffle [{:label (tr "labels.select-option")
                         :value "" :key "questions:what-brings-you-here"
                         :disabled true}
                        {:label (tr "onboarding.questions.reasons.exploring")
                         :value "discover-more-about-penpot"
                         :key "discover-more-about-penpot"}
                        {:label (tr "onboarding.questions.reasons.fit")
                         :value "test-penpot-to-see-if-its-a-fit-for-team"
                         :key "test-penpot-to-see-if-its-a-fit-for-team"}
                        {:label (tr "onboarding.questions.reasons.alternative")
                         :value "alternative-to-figma"
                         :key "alternative-to-figma"}
                        {:label (tr "onboarding.questions.reasons.testing")
                         :value "try-out-before-using-penpot-on-premise"
                         :key "try-out-before-using-penpot-on-premise"}])
              (conj {:label (tr "labels.other-short") :value "other"})))

        current-planning
        (dm/get-in @form [:data :planning])]

    [:& step-container {:form form
                        :step 3
                        :label "questions:about-your-job"
                        :on-next on-next
                        :on-prev on-prev
                        :class (stl/css :step-3)}

     [:div {:class (stl/css :paginator)} (str/ffmt "3/%" (if @show-step-3 4 3))]

     [:h1 {:class (stl/css :modal-title)}
      (tr "onboarding.questions.step3.title")]
     [:div {:class (stl/css :modal-question)}
      [:h3 {:class (stl/css :modal-subtitle)}
       (tr "onboarding.questions.step1.question2")]

      [:& fm/select
       {:options planning-options
        :select-class (stl/css :select-class)
        :default ""
        :name :planning
        :dropdown-class (stl/css :question-dropdown)}]]

     (when (= current-planning "other")
       [:& fm/input {:name :planning-other
                     :class (stl/css :input-spacing)
                     :placeholder (tr "labels.other")
                     :show-error false
                     :label ""}])

     [:div {:class (stl/css :modal-question)}
      [:h3 {:class (stl/css :modal-subtitle)} (tr "onboarding.questions.step3.question3")]
      [:& fm/select {:options team-size-options
                     :default ""
                     :select-class (stl/css :select-class)
                     :name :team-size}]]]))

(def ^:private schema:questions-form-4
  [:and
   [:map {:title "QuestionsFormStep4"}
    [:start-with
     [:enum "ui" "wireframing" "prototyping" "ds" "code" "other"]]
    [:start-with-other {:optional true} [::sm/text {:max 512}]]]

   [:fn {:error/field :start-with-other}
    (fn [{:keys [start-with start-with-other]}]
      (or (not= start-with "other")
          (and (= start-with "other")
               (not (str/blank? start-with-other)))))]])

(mf/defc step-4
  {::mf/props :obj}
  [{:keys [on-next on-prev form show-step-3]}]
  (let [start-options
        (mf/with-memo []
          (-> (shuffle [{:label (tr "onboarding.questions.start-with.ui")
                         :value "ui" :image "images/form/Design.png"}
                        {:label (tr "onboarding.questions.start-with.wireframing")
                         :value "wireframing" :image "images/form/templates.png"}
                        {:label (tr "onboarding.questions.start-with.prototyping")
                         :value "prototyping" :image "images/form/Prototype.png"}
                        {:label (tr "onboarding.questions.start-with.ds")
                         :value "ds" :image "images/form/components.png"}
                        {:label (tr "onboarding.questions.start-with.code")
                         :value "code" :image "images/form/design-and-dev.png"}])
              (conj {:label (tr "labels.other-short") :value "other" :icon deprecated-icon/curve})))

        current-start (dm/get-in @form [:data :start-with])

        on-start-change
        (mf/use-fn
         (mf/deps current-start)
         (fn [_ _]
           (when (not= current-start "other")
             (swap! form d/dissoc-in [:data :start-with-other])
             (swap! form d/dissoc-in [:errors :start-with-other]))))]

    [:& step-container {:form form
                        :step 4
                        :label "questions:how-start"
                        :on-next on-next
                        :on-prev on-prev
                        :class (stl/css :step-4)}

     [:div {:class (stl/css :paginator)} (str/ffmt "%/%" (if @show-step-3 4 3) (if @show-step-3 4 3))]

     [:h1 {:class (stl/css :modal-title)} (tr "onboarding.questions.step4.title")]
     [:div {:class (stl/css :radio-wrapper)}
      [:& fm/image-radio-buttons {:options start-options
                                  :img-width "159px"
                                  :img-height "120px"
                                  :name :start-with
                                  :on-change on-start-change}]

      (when (= current-start "other")
        [:& fm/input {:name :start-with-other
                      :class (stl/css :input-spacing)
                      :label ""
                      :show-error false
                      :placeholder (tr "labels.other")}])]]))



(mf/defc questions-modal
  []
  (let [container   (mf/use-ref)
        step        (mf/use-state 1)
        clean-data  (mf/use-state {})
        show-step-3 (mf/use-state false)

        ;; Forms are initialized here because we can go back and forth between the steps
        ;; and we want to keep the filled info
        step-1-form (fm/use-form
                     :initial {}
                     :schema schema:questions-form-1)

        step-2-form (fm/use-form
                     :initial {}
                     :schema schema:questions-form-2)

        step-3-form (fm/use-form
                     :initial {}
                     :schema schema:questions-form-3)

        step-4-form (fm/use-form
                     :initial {}
                     :schema schema:questions-form-4)

        on-next
        (mf/use-fn
         (fn [form]
           (when (:expected-use (:clean-data @form))
             (if (= (:expected-use (:clean-data @form)) "work")
               (reset! show-step-3 true)
               (reset! show-step-3 false)))
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
           (let [data (merge @clean-data (:clean-data @form))]
             (reset! clean-data data)
             (st/emit! (du/mark-questions-as-answered data)))))]

    [:div {:class (stl/css-case
                   :modal-overlay true)}
     [:div {:class (stl/css :modal-container)
            :ref container}

      (case @step
        1 [:& step-1 {:on-next on-next :on-prev on-prev :form step-1-form :show-step-3 show-step-3}]
        2 [:& step-2 {:on-next on-next :on-prev on-prev :form step-2-form :show-step-3 show-step-3}]
        3 (if @show-step-3
            [:& step-3 {:on-next on-next :on-prev on-prev :form step-3-form :show-step-3 show-step-3}]
            [:& step-4 {:on-next on-submit :on-prev on-prev :form step-4-form :show-step-3 show-step-3}])
        (when @show-step-3
          4 [:& step-4 {:on-next on-submit :on-prev on-prev :form step-4-form :show-step-3 show-step-3}]))]]))
