;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.releases.v2-18
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.ui.releases.common :as c]
   [rumext.v2 :as mf]))

(defmethod c/render-release-notes "2.18"
  [{:keys [slide klass next finish navigate version]}]
  (mf/html
   (case slide
     :start
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.18-slide-0.jpg"
               :class (stl/css :start-image)
               :border "0"
               :alt "Penpot 2.18 is here!"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "What’s new in Penpot?"]

          [:div {:class (stl/css :version-tag)}
           (dm/str "Version " version)]]

         [:div {:class (stl/css :features-block)}

          [:p  {:class (stl/css :feature-content)}
           "Say hello to Penpot Enterprise, our new paid plan, shipping alongside the most requested stroke to path functionality and a fresh batch of features and fixes."]

          [:p  {:class (stl/css :feature-content)}
           "Penpot stays free, unlimited, and open source. Enterprise is for the moment your design work grows and you're ready to govern it with confidence."]

          [:p  {:class (stl/css :feature-content)}
           "Let’s dive in!"]]

         [:div {:class (stl/css :navigation)}
          [:button {:class (stl/css :next-btn)
                    :on-click next} "Continue"]]]]]]

     0
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.18-enterprise.jpg"
               :class (stl/css :start-image)
               :border "0"
               :alt "Introducing Penpot Enterprise"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "Introducing Penpot Enterprise"]]

         [:div {:class (stl/css :feature)}
          [:p {:class (stl/css :feature-content)}
           "As the people and the work multiply, so does the question every admin faces: who can do what, and where? Enterprise gives you a clear answer."]

          [:p {:class (stl/css :feature-content)}
           "From a single Admin Console, you govern your whole organization in one place, setting the rules once so they apply everywhere the instant you make a change."]]

         [:div {:class (stl/css :navigation)}
          [:> c/navigation-bullets*
           {:slide slide
            :navigate navigate
            :total 4}]

          [:button {:on-click next
                    :class (stl/css :next-btn)} "Continue"]]]]]]

     1
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.18-sso.jpg"
               :class (stl/css :start-image)
               :border "0"
               :alt "One Sign in for everyone"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "One Sign in for everyone"]]

         [:div {:class (stl/css :feature)}
          [:p {:class (stl/css :feature-content)}
           "Let your whole organization sign in through your own corporate identity provider, whether that's a generic OpenID Connect provider, Azure Active Directory, or Google."]

          [:p {:class (stl/css :feature-content)}
           "One consistent way into your organization’s teams and files, governed by the directory you already run. From the Admin Console, you’ll be able to configure your identity provider setup to Penpot in just a few steps."]]

         [:div {:class (stl/css :navigation)}
          [:> c/navigation-bullets*
           {:slide slide
            :navigate navigate
            :total 4}]

          [:button {:on-click next
                    :class (stl/css :next-btn)} "Continue"]]]]]]

     2
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.18-permissions.jpg"
               :class (stl/css :start-image)
               :border "0"
               :alt "Advanced permissions"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "Advanced permissions"]]

         [:div {:class (stl/css :feature)}
          [:p {:class (stl/css :feature-content)}
           "Decide who can do what across every team at once, who can create, edit, administer teams, projects, and files, and who's allowed to invite new people in."]

          [:p {:class (stl/css :feature-content)}
           "Your rules sit on top of everyone's normal role, so the whole organization stays aligned with how you want to work, and every change takes effect the moment you make it."]]

         [:div {:class (stl/css :navigation)}
          [:> c/navigation-bullets*
           {:slide slide
            :navigate navigate
            :total 4}]

          [:button {:on-click next
                    :class (stl/css :next-btn)} "Continue"]]]]]]

     3
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.18-stroke2path.gif"
               :class (stl/css :start-image)
               :border "0"
               :alt "Stroke to Paths and new drawing tools"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "Stroke to Paths and new drawing tools"]]

         [:div {:class (stl/css :feature)}
          [:p {:class (stl/css :feature-content)}
           "Paths can now convert their stroke into a separate, editable path shape, letting you reshape or style the outline independently of the original path. Currently available under the new WebGL renderer."]

          [:p {:class (stl/css :feature-content)}
           "Lots of smaller wins add up this cycle. A few favorites:"]

          [:p {:class (stl/css :feature-content)}
           "• Draw faster with new shape and free-draw tool flyouts in the toolbar."]
          [:p {:class (stl/css :feature-content)}
           "• Dedicated Line and Arrow drawing tools (by @davidv399)."]

          [:p {:class (stl/css :feature-content)}
           "On top of that, 2.18 ships plugin API improvements, a broad round of bug fixes, and community-contributed fixes (thanks to @Krishcode264, @filipsajdak, @sawirricardo, and many more)."]]

         [:div {:class (stl/css :navigation)}

          [:> c/navigation-bullets*
           {:slide slide
            :navigate navigate
            :total 4}]

          [:button {:on-click finish
                    :class (stl/css :next-btn)} "Let's go"]]]]]])))