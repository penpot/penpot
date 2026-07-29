;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.releases.v2-16
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.ui.releases.common :as c]
   [rumext.v2 :as mf]))

(defmethod c/render-release-notes "2.16"
  [{:keys [slide klass next finish navigate version]}]
  (mf/html
   (case slide
     :start
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.16-slide-0.jpg"
               :class (stl/css :start-image)
               :border "0"
               :alt "Penpot 2.16 is here!"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "What’s new in Penpot?"]

          [:div {:class (stl/css :version-tag)}
           (dm/str "Version " version)]]

         [:div {:class (stl/css :features-block)}

          [:p  {:class (stl/css :feature-content)}
           "2.16 brings design tokens into the Design sidebar next to the numbers you already tune for layout, so choosing and checking tokens stays in the same flow. It also introduces an optional WebGL path (beta) for a faster canvas when files grow heavy, plus a wide layer of fixes and polish so the app simply feels more dependable from session to session."]

          [:p  {:class (stl/css :feature-content)}
           "This cycle ships on the order of fifty enhancements and sixty bug fixes, so the day-to-day gain is less about one headline and more about many small wins adding up. A large share of that work came from community contributors; we highlight a few names on a later slide, and we are grateful to everyone who helped move Penpot forward."]

          [:p  {:class (stl/css :feature-content)}
           "Let’s dive in!"]]

         [:div {:class (stl/css :navigation)}
          [:button {:class (stl/css :next-btn)
                    :on-click next} "Continue"]]]]]]

     0
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.16-tokens.gif"
               :class (stl/css :start-image)
               :border "0"
               :alt "Design tokens: visible and actionable from the Design tab"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "Design tokens: visible and actionable from the Design tab"]]

         [:div {:class (stl/css :feature)}
          [:p {:class (stl/css :feature-content)}
           "You can treat tokens as part of the Design sidebar: see what is bound, pick a token, or type a value with less jumping back to the tokens panel. The payoff is calmer work—fewer context switches when you are sizing, spacing, or tuning type and effects—and more confidence that what you see in the inspector matches what your system actually uses."]

          [:p {:class (stl/css :feature-content)}
           "This closes a long standing gap in the tokens roadmap: token picking and visibility belong in the Design panel itself. Token choices stay visible next to the controls you touch all day, so applying and checking design language feels like one continuous step instead of a detour."]]

         [:div {:class (stl/css :navigation)}
          [:> c/navigation-bullets*
           {:slide slide
            :navigate navigate
            :total 5}]

          [:button {:on-click next
                    :class (stl/css :next-btn)} "Continue"]]]]]]

     1
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.16-render.jpg"
               :class (stl/css :start-image)
               :border "0"
               :alt "WebGL rendering (beta): a faster canvas you can try now"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "WebGL rendering (beta): a faster canvas you can try now"]]

         [:div {:class (stl/css :feature)}
          [:p {:class (stl/css :feature-content)}
           "WebGL is a genuine upgrade to how the canvas moves. On busy boards and heavy files, pan and zoom should feel quicker and more immediate—less friction between what you want to see and what appears on screen—so your attention stays on the design instead of on the viewport catching up."]

          [:p {:class (stl/css :feature-content)}
           "We are still hardening it with real work in the wild, so it ships as beta and stays off until you enable it in user settings. Turn it on for your toughest file, ride the speedup, and send feedback from the product if anything misbehaves so we can keep improving it together."]]

         [:div {:class (stl/css :navigation)}
          [:> c/navigation-bullets*
           {:slide slide
            :navigate navigate
            :total 5}]

          [:button {:on-click next
                    :class (stl/css :next-btn)} "Continue"]]]]]]

     2
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.16-find-replace.gif"
               :class (stl/css :start-image)
               :border "0"
               :alt "Community contributions: volume up, quality even higher"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "Community contributions: volume up, quality even higher"]]

         [:div {:class (stl/css :feature)}
          [:p {:class (stl/css :feature-content)}
           "This release carried an unusually broad set of improvements with a community byline—not one or two patches at the edge, but a visible wave of quality-of-life work from people shipping Penpot alongside their own projects."]

          [:p {:class (stl/css :feature-content)}
           "That mix keeps the product grounded in how files actually behave in the wild, and we are glad to share the credit. In the long run, this is about ideas in motion: fresh workflows, clever affordances, and “why has nobody fixed this yet?” moments that expand what Penpot is."]]

         [:div {:class (stl/css :navigation)}
          [:> c/navigation-bullets*
           {:slide slide
            :navigate navigate
            :total 5}]

          [:button {:on-click next
                    :class (stl/css :next-btn)} "Continue"]]]]]]

     3
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.16-inputs-drag.gif"
               :class (stl/css :start-image)
               :border "0"
               :alt "Community contributions: volume up, quality even higher"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "Community contributions: volume up, quality even higher"]]

         [:div {:class (stl/css :feature)}
          [:p {:class (stl/css :feature-content)}
           "A few highlights:"]

          [:p {:class (stl/css :feature-content)}
           "• Drag to change on numeric inputs in the workspace sidebar — @RenzoMXD"]
          [:p {:class (stl/css :feature-content)}
           "• Loader feedback while importing and exporting files — @moorsecopers99"]
          [:p {:class (stl/css :feature-content)}
           "• Import tokens from a linked library — @dfelinto"]
          [:p {:class (stl/css :feature-content)}
           "• Find and replace for text content and layer names — @statxc"]
          [:p {:class (stl/css :feature-content)}
           "• Clickable links in comments — @eureka0928"]
          [:p {:class (stl/css :feature-content)}
           "• Read-only preview for saved versions — @wdeveloper16 "]

          [:p {:class (stl/css :feature-content)}
           " ... and many, many more."]]

         [:div {:class (stl/css :navigation)}
          [:> c/navigation-bullets*
           {:slide slide
            :navigate navigate
            :total 5}]

          [:button {:on-click next
                    :class (stl/css :next-btn)} "Continue"]]]]]]

     4
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.16-fixes.gif"
               :class (stl/css :start-image)
               :border "0"
               :alt "Stability, performance, and “everything else” (there is a lot)"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "Stability, performance, and “everything else” (there is a lot)"]]

         [:div {:class (stl/css :feature)}
          [:p {:class (stl/css :feature-content)}
           "Alongside the visible features, 2.16 clears a lot of rough edges: regressions, corner cases, and small frictions that only register once they stop getting in your way."]

          [:p {:class (stl/css :feature-content)}
           "On the enhancement side, think on the order of ~50 quality of life improvements. Together with the fixes, you’re looking at more than a hundred tracked improvements in this cycle, which is why the release feels “radical” in day to day feel even when no single line item explains it."]]
         [:div {:class (stl/css :navigation)}

          [:> c/navigation-bullets*
           {:slide slide
            :navigate navigate
            :total 5}]

          [:button {:on-click finish
                    :class (stl/css :next-btn)} "Let's go"]]]]]])))

