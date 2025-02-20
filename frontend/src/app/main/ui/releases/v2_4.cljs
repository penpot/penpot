;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.releases.v2-4
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.ui.releases.common :as c]
   [rumext.v2 :as mf]))

;; TODO: Review all copies and alt text
(defmethod c/render-release-notes "2.4"
  [{:keys [slide klass next finish navigate version]}]
  (mf/html
   (case slide
     :start
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.4-slide-0.jpg"
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
           "At Penpot we are at full speed!"]

          [:p  {:class (stl/css :feature-content)}
           "With the release of the long-awaited Plugins System still fresh, this 2.4 brings improvements in a wide range of areas that will serve a variety of use cases."]

          [:p  {:class (stl/css :feature-content)}
           "This release combines some of the most requested features—such as versioning and the viewer-only role—with performance improvements and a new .penpot format that will streamline the export of files and assets."]

          [:p  {:class (stl/css :feature-content)}
           "Let’s dive in!"]]

         [:div {:class (stl/css :navigation)}
          [:button {:class (stl/css :next-btn)
                    :on-click next} "Continue"]]]]]]

     0
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.4-viewer.gif"
               :class (stl/css :start-image)
               :border "0"
               :alt "Viewer role, designed to enhance collaboration"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "Viewer role, designed to enhance collaboration"]]

         [:div {:class (stl/css :feature)}
          [:p {:class (stl/css :feature-content)}
           "Collaboration takes many forms, and sometimes the risk of making unwanted or accidental adjustments can be a barrier to engaging with a design file."]

          [:p {:class (stl/css :feature-content)}
           "Now, you can invite members to your teams who only need to view and comment on files. Team members, stakeholders, developers… pick your case. Anyone who doesn't need to edit can participate confidently."]]

         [:div {:class (stl/css :navigation)}
          [:& c/navigation-bullets
           {:slide slide
            :navigate navigate
            :total 3}]

          [:button {:on-click next
                    :class (stl/css :next-btn)} "Continue"]]]]]]

     1
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.4-history.gif"
               :class (stl/css :start-image)
               :border "0"
               :alt "A timeline for your design process"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "A timeline for your design process"]]

         [:div {:class (stl/css :feature)}
          [:p {:class (stl/css :feature-content)}
           "Version History allows you to save different stages of your design process, so you can revisit them whenever needed."]

          [:p {:class (stl/css :feature-content)}
           "Some versions are saved automatically, serving as an invaluable emergency backup. Additionally, you can manually save versions, giving you full control over the timeline associated with a file. This way, you can always restore specific versions that you've intentionally saved."]]

         [:div {:class (stl/css :navigation)}
          [:& c/navigation-bullets
           {:slide slide
            :navigate navigate
            :total 3}]

          [:button {:on-click next
                    :class (stl/css :next-btn)} "Continue"]]]]]]

     2
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.4-format.gif"
               :class (stl/css :start-image)
               :border "0"
               :alt "New export format: fast and open"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "New export format: fast and open"]]
         [:div {:class (stl/css :feature)}
          [:p {:class (stl/css :feature-content)}
           "The new .penpot format will streamline the import and export of files and assets by being more efficient and interoperable."]
          [:p {:class (stl/css :feature-content)}
           "This format replaces the previous two—so no more choosing between them or accidentally picking the wrong one! It's better for both scenarios: if you just need to import or export files quickly, it’ll be a bit faster. And if you want to extract data (like a list of color assets), this new format is much easier to read."]]

         [:div {:class (stl/css :navigation)}

          [:& c/navigation-bullets
           {:slide slide
            :navigate navigate
            :total 3}]

          [:button {:on-click finish
                    :class (stl/css :next-btn)} "Let's go"]]]]]])))

