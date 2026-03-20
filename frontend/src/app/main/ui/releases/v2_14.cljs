;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.releases.v2-14
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.ui.releases.common :as c]
   [rumext.v2 :as mf]))

(defmethod c/render-release-notes "2.14"
  [{:keys [slide klass next finish navigate version]}]
  (mf/html
   (case slide
     :start
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.14-slide-0.jpg"
               :class (stl/css :start-image)
               :border "0"
               :alt "Penpot 2.14 is here!"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "What’s new in Penpot?"]

          [:div {:class (stl/css :version-tag)}
           (dm/str "Version " version)]]

         [:div {:class (stl/css :features-block)}
          [:span {:class (stl/css :feature-title)}
           "Design tokens, but friendlier (and a bit faster, too)"]

          [:p  {:class (stl/css :feature-content)}
           "This release keeps pushing Penpot’s design system foundations forward, with a big focus on design tokens. We’re making long token names easier to navigate, opening up tokens in the plugins API, and tackling one of the trickiest moments in token workflows: renaming (without breaking everything)."]

          [:p  {:class (stl/css :feature-content)}
           "On top of that, you’ll find a handful of quality-of-life improvements and some performance work in the sidebar to keep things feeling smooth as your files grow. Let’s dive in."]

          [:p  {:class (stl/css :feature-content)}
           "Let’s dive in!"]]

         [:div {:class (stl/css :navigation)}
          [:button {:class (stl/css :next-btn)
                    :on-click next} "Continue"]]]]]]

     0
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.14-tokens-fold.gif"
               :class (stl/css :start-image)
               :border "0"
               :alt "Token groups: Navigating long names, finally"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "Token groups: Navigating long names, finally"]]

         [:div {:class (stl/css :feature)}
          [:p {:class (stl/css :feature-content)}
           "Token names are rarely short and sweet. Most of the time they carry a lot of meaning (type, state, property, variant… and more), which is great for consistency, but not so great for browsing. In 2.14 we’re introducing token groups, a new way to navigate dotted token paths as nested, collapsible sections."]

          [:p {:class (stl/css :feature-content)}
           "Token segments before the final name are displayed as groups, and only the last segment stays as a pill (so you keep the familiar token “chip” where it matters). If you unfold a path, it stays open while you move around the app (it resets only when the page reloads). And when you create a new token, Penpot automatically unfolds the path needed to reveal it (even if it overrides a previously opened one)."]

          [:p {:class (stl/css :feature-content)}
           "One extra detail: if you edit the path and change group segments, the token is moved to its new group (creating it if needed), and empty groups are automatically cleaned up."]]

         [:div {:class (stl/css :navigation)}
          [:& c/navigation-bullets
           {:slide slide
            :navigate navigate
            :total 4}]

          [:button {:on-click next
                    :class (stl/css :next-btn)} "Continue"]]]]]]

     1
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.14-api.gif"
               :class (stl/css :start-image)
               :border "0"
               :alt "Design tokens in the plugins API: Automation unlocked"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "Design tokens in the plugins API: Automation unlocked"]]

         [:div {:class (stl/css :feature)}
          [:p {:class (stl/css :feature-content)}
           "Design tokens are now available in the Penpot plugins API. That means plugins (and external tools built around Penpot, like AI clients or Penpot MCP) can finally work with tokens programmatically and automate token workflows that used to be purely manual."]

          [:p {:class (stl/css :feature-content)}
           "If you’ve been waiting to generate tokens, sync them, or manipulate them from your own tools, this is the missing piece. And yes, this one has been requested a lot."]]

         [:div {:class (stl/css :navigation)}
          [:& c/navigation-bullets
           {:slide slide
            :navigate navigate
            :total 4}]

          [:button {:on-click next
                    :class (stl/css :next-btn)} "Continue"]]]]]]

     2
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.14-remap.jpg"
               :class (stl/css :start-image)
               :border "0"
               :alt "Rename tokens without breaking everything"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "Rename tokens without breaking everything"]]

         [:div {:class (stl/css :feature)}
          [:p {:class (stl/css :feature-content)}
           "Renaming tokens sounds simple until you remember the references. One change can ripple through aliases, applied tokens, tooltips, math operations… and suddenly you’re left with a broken chain. In 2.14, renaming a token can optionally remap its references, keeping connections intact and updating the design with the new token name."]

          [:p {:class (stl/css :feature-content)}
           "Remapping is always optional, because sometimes you don’t want to keep the current connections. When enabled, it affects all tokens in the file and also takes libraries into account, so main components can propagate changes to child components, and applied tokens update on the elements using them."]]

         [:div {:class (stl/css :navigation)}
          [:& c/navigation-bullets
           {:slide slide
            :navigate navigate
            :total 4}]

          [:button {:on-click next
                    :class (stl/css :next-btn)} "Continue"]]]]]]

     3
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.14-icons.gif"
               :class (stl/css :start-image)
               :border "0"
               :alt "Quality-of-life improvements"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "Quality-of-life improvements"]]
         [:div {:class (stl/css :feature)}

          [:p {:class (stl/css :feature-content)}
           "Lock and hide controls in the layer panel are getting a usability boost. The lock and visibility icons stay fixed in a right-aligned column regardless of indentation, and scrolling won’t make them awkward to click (even in deeply nested files)."]

          [:p {:class (stl/css :feature-content)}
           "We’re also improving sidebar performance, with a focus on keeping interactions fluent. The goal is to lazy-load the shape list on-demand and avoid UI stalls when clicking or hovering around the sidebar."]

          [:p {:class (stl/css :feature-content)}
           "And one more: you can now use Shift/Alt arrow key stepping in color picker inputs (a community contribution by @eureka928. ❤️)"]]

         [:div {:class (stl/css :navigation)}

          [:& c/navigation-bullets
           {:slide slide
            :navigate navigate
            :total 4}]

          [:button {:on-click finish
                    :class (stl/css :next-btn)} "Let's go"]]]]]])))

