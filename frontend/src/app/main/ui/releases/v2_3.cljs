;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.releases.v2-3
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.ui.releases.common :as c]
   [rumext.v2 :as mf]))

;; TODO: Review all copies and alt text
(defmethod c/render-release-notes "2.3"
  [{:keys [slide klass next finish navigate version]}]
  (mf/html
   (case slide
     :start
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.3-slide-0.png"
               :class (stl/css :start-image)
               :border "0"
               :alt "A graphic illustration with Penpot style"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "What’s new in Penpot?"]

          [:div {:class (stl/css :version-tag)}
           (dm/str "Version " version)]]

         [:div {:class (stl/css :features-block)}
          [:span {:class (stl/css :feature-title)}
           "Penpot can now be extended by using Plugins!"]

          [:p  {:class (stl/css :feature-content)}
           "The introduction of our brand new Plugin system allows you to access even richer ecosystem of capabilities."]

          [:p  {:class (stl/css :feature-content)}
           "We are beyond excited about how this will further involve the Penpot community in building the best design and prototyping platform."]

          [:p  {:class (stl/css :feature-content)}
           "Let’s dive in!"]]

         [:div {:class (stl/css :navigation)}
          [:button {:class (stl/css :next-btn)
                    :on-click next} "Continue"]]]]]]

     0
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.3-img-slide-1.gif"
               :class (stl/css :start-image)
               :border "0"
               :alt "Build Plugins to enhance your workflow"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "Build Plugins and enhance your workflow"]]

         [:div {:class (stl/css :feature)}
          [:p {:class (stl/css :feature-content)}
           "Penpot Plugins encourage developers to easily customize and expand the platform using standard web technologies like JavaScript, CSS, and HTML."]

          [:p {:class (stl/css :feature-content)}
           "Find everything you need in our full comprehensive documentation to start building your plugins now!"]]

         [:div {:class (stl/css :navigation)}
          [:& c/navigation-bullets
           {:slide slide
            :navigate navigate
            :total 2}]

          [:button {:on-click next
                    :class (stl/css :next-btn)} "Continue"]]]]]]


     1
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.3-img-slide-2.gif"
               :class (stl/css :start-image)
               :border "0"
               :alt "Plugins are safe and extremely easy to use"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "Plugins are safe and extremely easy to use"]]
         [:div {:class (stl/css :feature)}
          [:p {:class (stl/css :feature-content)}
           "Penpot plugins are quite easy to install."]
          [:p {:class (stl/css :feature-content)}
           "Be sure to keep an eye on our evolving " [:a {:href "https://penpot.app/penpothub" :target "_blank"} "Penpot Hub"] " to pick the ones that are best suited to enhance your workflow."]

          [:p {:class (stl/css :feature-content)}
           "This is just the beginning of a myriad of possibilities. Let’s build this community together ❤️."]]

         [:div {:class (stl/css :navigation)}

          [:& c/navigation-bullets
           {:slide slide
            :navigate navigate
            :total 2}]

          [:button {:on-click finish
                    :class (stl/css :next-btn)} "Let's go"]]]]]])))

