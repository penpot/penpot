;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.releases.v2-17
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.ui.releases.common :as c]
   [rumext.v2 :as mf]))

(defmethod c/render-release-notes "2.17"
  [{:keys [slide klass next finish navigate version]}]
  (mf/html
   (case slide
     :start
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.17-slide-0.jpg"
               :class (stl/css :start-image)
               :border "0"
               :alt "Penpot 2.17 is here!"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "What’s new in Penpot?"]

          [:div {:class (stl/css :version-tag)}
           (dm/str "Version " version)]]

         [:div {:class (stl/css :features-block)}

          [:p  {:class (stl/css :feature-content)}
           "Improvements across WebGL rendering, tokens, MCP, and quality-of-life design features."]

          [:p  {:class (stl/css :feature-content)}
           "2.17 brings background blur to open up new graphic possibilities, without leaving the canvas, surfaces typography tokens directly in the Design sidebar, and lets you use comments while you design. WebGL rendering and MCP both keep getting stronger, backed by performance improvements, a wide round of bug fixes, and plenty of quality-of-life polish so every session feels smoother."]

          [:p  {:class (stl/css :feature-content)}
           "Let’s dive in!"]]

         [:div {:class (stl/css :navigation)}
          [:button {:class (stl/css :next-btn)
                    :on-click next} "Continue"]]]]]]

     0
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.17-render.jpg"
               :class (stl/css :start-image)
               :border "0"
               :alt "WebGL rendering: still beta, getting stronger"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "WebGL rendering: still beta, getting stronger"]]

         [:div {:class (stl/css :feature)}
          [:p {:class (stl/css :feature-content)}
           "WebGL rendering keeps maturing. The prototype viewer now runs on the same engine, guides render in WebGL, and a wave of fixes makes heavy files more dependable."]

          [:p {:class (stl/css :feature-content)}
           "It is still beta and off by default, but we genuinely encourage you to turn it on from user settings: it is already a better, faster path and it is where Penpot is heading. And if you spot anything odd, give us a shout: all your feedback is hugely welcome."]]

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
        [:img {:src "images/features/2.17-blur.gif"
               :class (stl/css :start-image)
               :border "0"
               :alt "Background blur is here"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "Background blur is here"]]

         [:div {:class (stl/css :feature)}
          [:p {:class (stl/css :feature-content)}
           "A long-awaited effect lands: background blur. Blur whatever sits behind a layer to add depth, focus, and frosted-glass looks, expanding what you can create on the canvas."]

          [:p {:class (stl/css :feature-content)}
           "Find it in the Design sidebar alongside your other effects. More creative range, no workarounds."]]

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
        [:img {:src "images/features/2.17-mcp.gif"
               :class (stl/css :start-image)
               :border "0"
               :alt "MCP: easier to connect, more reliable"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "MCP: easier to connect, more reliable"]]

         [:div {:class (stl/css :feature)}
          [:p {:class (stl/css :feature-content)}
           "MCP feels more at home in Penpot. A status button gives you single-tab connection control, your API key is visible on the Integrations page, and sync between the server and plugins is much more reliable."]

          [:p {:class (stl/css :feature-content)}
           "Self-hosters get better multi-instance support and clearer proxy examples. If you connect AI to your files, this release is worth revisiting."]]

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
        [:img {:src "images/features/2.17-tokens.gif"
               :class (stl/css :start-image)
               :border "0"
               :alt "Design tokens: more visible, more responsive"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "Design tokens: more visible, more responsive"]]

         [:div {:class (stl/css :feature)}
          [:p {:class (stl/css :feature-content)}
           "Design-system work gets clearer and faster:"]

          [:p {:class (stl/css :feature-content)}
           "• Typography tokens now appear when you multi-select text."]
          [:p {:class (stl/css :feature-content)}
            "• The Design sidebar gains a composite typography input."]
          [:p {:class (stl/css :feature-content)}
            "• Autocomplete speeds up token creation forms."]
          [:p {:class (stl/css :feature-content)}
            "• Dropdowns surface more context at a glance."]
          [:p {:class (stl/css :feature-content)}
            "• Token propagation is faster, a nice performance boost."]]
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
        [:img {:src "images/features/2.17-comments.jpg"
               :class (stl/css :start-image)
               :border "0"
               :alt "Quality-of-life, fixes, and performance"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "Quality-of-life, fixes, and performance"]]

         [:div {:class (stl/css :feature)}
          [:p {:class (stl/css :feature-content)}
           "Lots of smaller wins add up this cycle. A few favorites:"]

          [:p {:class (stl/css :feature-content)}
           "• Use comments while you design: read, reply, and resolve without switching out of design mode."]
          [:p {:class (stl/css :feature-content)}
           "• Color picker list view to scan swatches faster."]
          [:p {:class (stl/css :feature-content)}
           "• Import tokens from a linked library — @dfelinto"]
          [:p {:class (stl/css :feature-content)}
           "• Dashed strokes with dedicated dash and gap inputs (by @eureka0928)."]
          [:p {:class (stl/css :feature-content)}
           "• Selection size badge with color variants and positioning (by @bittoby)."]

          [:p {:class (stl/css :feature-content)}
           "On top of that, 2.17 ships performance improvements, a broad round of bug fixes, and many community-contributed enhancements (thanks to @FairyPigDev, @MilosM348, @Dexterity104, @RenzoMXD, and many more)."]]

         [:div {:class (stl/css :navigation)}

          [:> c/navigation-bullets*
           {:slide slide
            :navigate navigate
            :total 5}]

          [:button {:on-click finish
                    :class (stl/css :next-btn)} "Let's go"]]]]]])))