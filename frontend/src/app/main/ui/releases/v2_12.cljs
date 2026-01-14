;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.releases.v2-12
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.ui.releases.common :as c]
   [rumext.v2 :as mf]))

(defmethod c/render-release-notes "2.12"
  [{:keys [slide klass next finish navigate version]}]
  (mf/html
   (case slide
     :start
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.12-slide-0.jpg"
               :class (stl/css :start-image)
               :border "0"
               :alt "Penpot 2.12 is here!"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "What’s new in Penpot?"]

          [:div {:class (stl/css :version-tag)}
           (dm/str "Version " version)]]

         [:div {:class (stl/css :features-block)}
          [:span {:class (stl/css :feature-title)}
           "Better tokens visibility and more!"]

          [:p  {:class (stl/css :feature-content)}
           "This release focuses on making your everyday workflow feel clearer, faster and more intuitive. Tokens are now easier to see and apply, appearing directly where you work and giving the designs better context during code inspection. Variants gain a more natural flow thanks to simple boolean toggles that remove friction when switching states. And PDF export becomes more flexible, letting you choose exactly which boards to share so your files match the story you want to tell."]

          [:p  {:class (stl/css :feature-content)}
           "Together, these enhancements bring greater control and fluidity to your entire design process."]

          [:p  {:class (stl/css :feature-content)}
           "Let’s dive in!"]]

         [:div {:class (stl/css :navigation)}
          [:button {:class (stl/css :next-btn)
                    :on-click next} "Continue"]]]]]]

     0
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.12-tokens-sidebar.gif"
               :class (stl/css :start-image)
               :border "0"
               :alt "Better tokens visibility"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "Better tokens visibility"]]

         [:div {:class (stl/css :feature)}
          [:p {:class (stl/css :feature-content)}
           "Design systems should be both powerful and effortless to use. This release brings tokens closer to where you work, making them easier to apply and easier to understand."]

          [:span {:class (stl/css :feature-title)}
           "Apply color tokens right from the sidebar"]

          [:p {:class (stl/css :feature-content)}
           "Your color tokens now appear directly in the properties sidebar, making it faster to apply or unapply tokens from the design tab. No more digging: now you can use tokens within your design flow."]

          [:span {:class (stl/css :feature-title)}
           "See token names in the Inspect panel"]

          [:p {:class (stl/css :feature-content)}
           "Developers now get a clearer context during handoff. The Inspect panel shows the actual token used in your design, in a similar way to how styles are displayed. This small detail reduces ambiguity, aligns everyone on the same language, and strengthens collaboration across the team."]]

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
        [:img {:src "images/features/2.12-variants.gif"
               :class (stl/css :start-image)
               :border "0"
               :alt "Simpler boolean variants"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "Simpler boolean variants"]]
         [:div {:class (stl/css :feature)}

          [:p {:class (stl/css :feature-content)}
           "Variants are central to building flexible, scalable components. With this release, boolean properties become far easier to work with."]

          [:span {:class (stl/css :feature-title)}
           "A simple toggle for boolean values"]

          [:p {:class (stl/css :feature-content)}
           "Binary states now use a clean toggle, to be able to switch visually,  instead of a dropdown. This makes adjusting component states more intuitive and speeds up working with multiple instances."]

          [:p {:class (stl/css :feature-content)}
           "It’s a subtle improvement, but it removes friction you feel hundreds of times a week, and makes component work flow more naturally."]]

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
        [:img {:src "images/features/2.12-export-pdf.gif"
               :class (stl/css :start-image)
               :border "0"
               :alt "Smarter PDF export"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "Smarter PDF export"]]
         [:div {:class (stl/css :feature)}

          [:p {:class (stl/css :feature-content)}
           "Exporting your work is now more precise and flexible."]

          [:span {:class (stl/css :feature-title)}
           "Select specific boards when exporting"]


          [:p {:class (stl/css :feature-content)}
           "You’re now in control of which boards make it into your PDF. Share just the final screens, just a flow, just the workshop materials. This streamlined export flow adapts to the way real teams work: share the story you want to tell, with exactly the boards you need."]]

         [:div {:class (stl/css :navigation)}

          [:& c/navigation-bullets
           {:slide slide
            :navigate navigate
            :total 3}]

          [:button {:on-click finish
                    :class (stl/css :next-btn)} "Let's go"]]]]]])))

