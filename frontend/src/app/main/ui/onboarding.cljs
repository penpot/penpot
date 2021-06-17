;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.onboarding
  (:require
   [app.common.spec :as us]
   [app.config :as cf]
   [app.main.data.dashboard :as dd]
   [app.main.data.messages :as dm]
   [app.main.data.modal :as modal]
   [app.main.data.users :as du]
   [app.main.store :as st]
   [app.main.ui.components.forms :as fm]
   [app.util.dom :as dom]
   [app.util.object :as obj]
   [app.util.router :as rt]
   [app.util.timers :as tm]
   [cljs.spec.alpha :as s]
   [rumext.alpha :as mf]))


;; --- ONBOARDING LIGHTBOX

(mf/defc navigation-bullets
  [{:keys [slide navigate total]}]
  [:ul.step-dots
   (for [i (range total)]
     [:li {:class (dom/classnames :current (= slide i))
           :on-click #(navigate i)}])])

(mf/defc onboarding-start
  [{:keys [next] :as props}]
  [:div.modal-container.onboarding
   [:div.modal-left.welcome
    [:img {:src "images/login-on.jpg" :border "0" :alt "Penpot"}]]
   [:div.modal-right
    [:div.modal-title
     [:h2 "Welcome to Penpot!"]]
    [:span.release "Alpha version " (:main @cf/version)]
    [:div.modal-content
     [:p "We are very happy to introduce you to the very first Alpha release."]
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

(mf/defc onboarding-team-modal
  {::mf/register modal/components
   ::mf/register-as :onboarding-team}
  []
  (let [close (mf/use-fn (st/emitf (modal/hide)))
        form  (fm/use-form :spec ::team-form
                           :initial {})
        on-success
        (mf/use-callback
         (fn [_form response]
           (st/emit! (modal/hide)
                     (rt/nav :dashboard-projects {:team-id (:id response)}))))

        on-error
        (mf/use-callback
         (fn [_form _response]
           (st/emit! (dm/error "Error on creating team."))))

        on-submit
        (mf/use-callback
         (fn [form _event]
           (let [mdata  {:on-success (partial on-success form)
                         :on-error   (partial on-error form)}
                 params {:name (get-in @form [:clean-data :name])}]
             (st/emit! (dd/create-team (with-meta params mdata))))))]

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


;;; --- RELEASE NOTES MODAL

(defmulti render-release-notes :version)

(mf/defc release-notes
  [{:keys [version] :as props}]
  (let [slide (mf/use-state :start)
        klass (mf/use-state "fadeInDown")

        navigate
        (mf/use-callback #(reset! slide %))

        next
        (mf/use-callback
         (mf/deps slide)
         (fn []
           (if (= @slide :start)
             (navigate 0)
             (navigate (inc @slide)))))

        finish
        (mf/use-callback
         (st/emitf (modal/hide)
                   (du/mark-onboarding-as-viewed {:version version})))
        ]

    (mf/use-effect
     (mf/deps)
     (fn []
       (st/emitf (du/mark-onboarding-as-viewed {:version version}))))

    (mf/use-layout-effect
     (mf/deps @slide)
     (fn []
       (when (not= :start @slide)
         (reset! klass "fadeIn"))
       (let [sem (tm/schedule 300 #(reset! klass nil))]
         (fn []
           (reset! klass nil)
           (tm/dispose! sem)))))

    (render-release-notes
     {:next next
      :navigate navigate
      :finish finish
      :klass klass
      :slide slide
      :version version})))

(mf/defc release-notes-modal
  {::mf/wrap-props false
   ::mf/register modal/components
   ::mf/register-as :release-notes}
  [props]
  (let [versions (methods render-release-notes)
        version  (obj/get props "version")]
    (when (contains? versions version)
      [:> release-notes props])))

(defmethod render-release-notes "0.0"
  [params]
  (render-release-notes (assoc params :version "1.6")))

(defmethod render-release-notes "1.4"
  [{:keys [slide klass next finish navigate version]}]
  (mf/html
   (case @slide
     :start
     [:div.modal-overlay
      [:div.animated {:class @klass}
       [:div.modal-container.onboarding.feature
        [:div.modal-left
         [:img {:src "images/login-on.jpg" :border "0" :alt "What's new Alpha release 1.4.0"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "What's new?"]]
         [:span.release "Alpha version " version]
         [:div.modal-content
          [:p "Penpot continues growing with new features that improve performance, user experience and visual design."]
          [:p "We are happy to show you a sneak peak of the most important stuff that the Alpha 1.4.0 version brings."]]
         [:div.modal-navigation
          [:button.btn-secondary {:on-click next} "Continue"]]]
        [:img.deco {:src "images/deco-left.png" :border "0"}]
        [:img.deco.right {:src "images/deco-right.png" :border "0"}]]]]

     0
     [:div.modal-overlay
      [:div.animated {:class @klass}
       [:div.modal-container.onboarding.feature
        [:div.modal-left
         [:img {:src "images/features/select-files.gif" :border "0" :alt "New file selection"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "New file selection and open files"]]
         [:div.modal-content
          [:p "Now you can select files with left click and make multi-selections holding down the shift + left click."]
          [:p "To open a file you just have to double click it. You can also open a file in a new tab with right click."]]
         [:div.modal-navigation
          [:button.btn-secondary {:on-click next} "Continue"]
          [:& navigation-bullets
           {:slide @slide
            :navigate navigate
            :total 4}]]]]]]

     1
     [:div.modal-overlay
      [:div.animated {:class @klass}
       [:div.modal-container.onboarding.feature
        [:div.modal-left
         [:img {:src "images/features/manage-files.gif" :border "0" :alt "Manage files"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "New files/projects management"]]
         [:div.modal-content
          [:p "Penpot now allows to duplicate and move files and projects."]
          [:p "Also, now you have an easy way to manage files and projects between teams."]]
         [:div.modal-navigation
          [:button.btn-secondary {:on-click next} "Continue"]
          [:& navigation-bullets
           {:slide @slide
            :navigate navigate
            :total 4}]]]]]]

     2
     [:div.modal-overlay
      [:div.animated {:class @klass}
       [:div.modal-container.onboarding.feature
        [:div.modal-left
         [:img {:src "images/features/rtl.gif" :border "0" :alt "RTL support"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "RTL support is now available!"]]
         [:div.modal-content
          [:p "Diversity and inclusion is one major Penpot concern and that's why we love to give support to RTL languages, unlike in most of design tools."]
          [:p "If you write in arabic, hebrew or other RTL language text direction will be automatically detected in text layers."]]
         [:div.modal-navigation
          [:button.btn-secondary {:on-click next} "Continue"]
          [:& navigation-bullets
           {:slide @slide
            :navigate navigate
            :total 4}]]]]]]

     3
     [:div.modal-overlay
      [:div.animated {:class @klass}
       [:div.modal-container.onboarding.feature
        [:div.modal-left
         [:img {:src "images/features/blend-modes.gif" :border "0" :alt "Blend modes"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "New layer opacity and blend modes"]]
         [:div.modal-content
          [:p "Combining elements visually is an important part of the design process."]
          [:p "This is why the standard blend modes and opacity level are now available for each element."]]
         [:div.modal-navigation
          [:button.btn-secondary {:on-click finish} "Start!"]
          [:& navigation-bullets
           {:slide @slide
            :navigate navigate
            :total 4}]]]]]])))

(defmethod render-release-notes "1.5"
  [{:keys [slide klass next finish navigate version]}]
  (mf/html
   (case @slide
     :start
     [:div.modal-overlay
      [:div.animated {:class @klass}
       [:div.modal-container.onboarding.feature
        [:div.modal-left
         [:img {:src "images/login-on.jpg" :border "0" :alt "What's new Alpha release 1.5.0"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "What's new?"]]
         [:span.release "Alpha version " version]
         [:div.modal-content
          [:p "Penpot continues growing with new features that improve performance, user experience and visual design."]
          [:p "We are happy to show you a sneak peak of the most important stuff that the Alpha 1.5.0 version brings."]]
         [:div.modal-navigation
          [:button.btn-secondary {:on-click next} "Continue"]]]
        [:img.deco {:src "images/deco-left.png" :border "0"}]
        [:img.deco.right {:src "images/deco-right.png" :border "0"}]]]]

     0
     [:div.modal-overlay
      [:div.animated {:class @klass}
       [:div.modal-container.onboarding.feature
        [:div.modal-left
         [:img {:src "images/features/path-tool.gif" :border "0" :alt "New path tool"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "New features for paths"]]
         [:div.modal-content
          [:p "Now you can select snap points on edition, add/remove nodes, merge/join/split nodes."]
          [:p "The usability and performance of the paths tool has been improved too."]]
         [:div.modal-navigation
          [:button.btn-secondary {:on-click next} "Continue"]
          [:& navigation-bullets
           {:slide @slide
            :navigate navigate
            :total 3}]]]]]]

     1
     [:div.modal-overlay
      [:div.animated {:class @klass}
       [:div.modal-container.onboarding.feature
        [:div.modal-left
         [:img {:src "images/features/assets-organiz.gif" :border "0" :alt "Manage libraries"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "New libraries organization"]]
         [:div.modal-content
          [:p "Penpot now allows to group, multiselect and bulk edition of assets (components and graphics)."]
          [:p "It is time to have all the libraries well organized and work more efficiently."]]
         [:div.modal-navigation
          [:button.btn-secondary {:on-click next} "Continue"]
          [:& navigation-bullets
           {:slide @slide
            :navigate navigate
            :total 3}]]]]]]

     2
     [:div.modal-overlay
      [:div.animated {:class @klass}
       [:div.modal-container.onboarding.feature
        [:div.modal-left
         [:img {:src "images/features/smart-inputs.gif" :border "0" :alt "Smart inputs"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "Smart inputs"]]
         [:div.modal-content
          [:p "Now you can have more precision in your designs with basic math operations in inputs."]
          [:p "It's easier to specify by how much you want to change a value and work with measures and distances."]]
         [:div.modal-navigation
          [:button.btn-secondary {:on-click finish} "Start!"]
          [:& navigation-bullets
           {:slide @slide
            :navigate navigate
            :total 3}]]]]]])))

(defmethod render-release-notes "1.6"
  [{:keys [slide klass next finish navigate version]}]
  (mf/html
   (case @slide
     :start
     [:div.modal-overlay
      [:div.animated {:class @klass}
       [:div.modal-container.onboarding.feature
        [:div.modal-left
         [:img {:src "images/login-on.jpg" :border "0" :alt "What's new Alpha release 1.6.0"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "What's new?"]]
         [:span.release "Alpha version " version]
         [:div.modal-content
          [:p "Penpot continues growing with new features that improve performance, user experience and visual design."]
          [:p "We are happy to show you a sneak peak of the most important stuff that the Alpha 1.6.0 version brings."]]
         [:div.modal-navigation
          [:button.btn-secondary {:on-click next} "Continue"]]]
        [:img.deco {:src "images/deco-left.png" :border "0"}]
        [:img.deco.right {:src "images/deco-right.png" :border "0"}]]]]

     0
     [:div.modal-overlay
      [:div.animated {:class @klass}
       [:div.modal-container.onboarding.feature
        [:div.modal-left
         [:img {:src "images/features/custom-fonts.gif" :border "0" :alt "Upload/use custom fonts"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "Upload/use custom fonts"]]
         [:div.modal-content
          [:p "From now on you can upload fonts to a Penpot team and use them across its files. This is one of the most requested features since our first release (we listen!)"]
          [:p "We hope you enjoy having more typography options and our brand new font selector."]]
         [:div.modal-navigation
          [:button.btn-secondary {:on-click next} "Continue"]
          [:& navigation-bullets
           {:slide @slide
            :navigate navigate
            :total 4}]]]]]]

     1
     [:div.modal-overlay
      [:div.animated {:class @klass}
       [:div.modal-container.onboarding.feature
        [:div.modal-left
         [:img {:src "images/features/scale-text.gif" :border "0" :alt "Interactively scale text"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "Scale text layers at resizing"]]
         [:div.modal-content
          [:p "New main menu option “Scale text (K)” to enable scale text mode."]
          [:p "Disabled by default, this tool is disabled back after being used."]]
         [:div.modal-navigation
          [:button.btn-secondary {:on-click next} "Continue"]
          [:& navigation-bullets
           {:slide @slide
            :navigate navigate
            :total 4}]]]]]]

     2
     [:div.modal-overlay
      [:div.animated {:class @klass}
       [:div.modal-container.onboarding.feature
        [:div.modal-left
         [:img {:src "images/features/performance.gif" :border "0" :alt "Performance improvements"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "Performance improvements"]]
         [:div.modal-content
          [:p "Penpot brings important improvements handling large files. The performance in managing files in the dashboard has also been improved."]
          [:p "You should have the feeling that files and layers show up a bit faster :)"]]
         [:div.modal-navigation
          [:button.btn-secondary {:on-click next} "Continue"]
          [:& navigation-bullets
           {:slide @slide
            :navigate navigate
            :total 4}]]]]]]

     3
     [:div.modal-overlay
      [:div.animated {:class @klass}
       [:div.modal-container.onboarding.feature
        [:div.modal-left
         [:img {:src "images/features/shapes-to-path.gif" :border "0" :alt "Shapes to path"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "Shapes to path"]]
         [:div.modal-content
          [:p "Now you can edit basic shapes like rectangles, circles and image containers by double clicking."]
          [:p "An easy way to increase speed by working with vectors!"]]
         [:div.modal-navigation
          [:button.btn-secondary {:on-click finish} "Start!"]
          [:& navigation-bullets
           {:slide @slide
            :navigate navigate
            :total 4}]]]]]])))
