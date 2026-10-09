;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.releases.v2-19
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.releases.common :as c]
   [rumext.v2 :as mf]))

(defmethod c/render-release-notes "2.19"
  [{:keys [slide klass next finish navigate version]}]
  (mf/html
   (case slide
     :start
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.19-slide-0.jpg"
               :class (stl/css :start-image)
               :border "0"
               :alt "Penpot 2.19 is here!"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "What’s new in Penpot?"]

          [:div {:class (stl/css :version-tag)}
           (dm/str "Version " version)]]

         [:div {:class (stl/css :features-block)}

          [:p  {:class (stl/css :feature-content)}
           "Per-side strokes, your own shortcuts, tokens shared in libraries, and a big step for WebGL rendering."]

          [:p  {:class (stl/css :feature-content)}
           "2.19 is a packed release. You can now set stroke width per side, customize your keyboard shortcuts, and draw and edit paths with far more control. Design tokens can now live in a shared library and stay in sync across files, and WebGL rendering gets better text, smoother canvases, and much faster exports. On top of that come performance improvements, a wide round of bug fixes, and plenty of quality-of-life polish."]]

         [:div {:class (stl/css :navigation)}
          [:> button* {:class (stl/css :next-btn)
                       :on-click next
                       :variant "primary"}
           "Continue"]]]]]]

     0
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.19-per-side-strokes.jpg"
               :class (stl/css :start-image)
               :border "0"
               :alt "Per-side strokes. Yes, finally!"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "Per-side strokes. Yes, finally!"]]

         [:div {:class (stl/css :feature)}
          [:p {:class (stl/css :feature-content)}
           "We know, this one kept you waiting. Rectangles and boards can now have a different stroke width on each side (top, right, bottom, left). Switch from uniform to per-side in the Stroke section, just like independent border radius. Need only a bottom divider or a left accent? No more extra shapes or masking tricks."]

          [:p {:class (stl/css :feature-content)}
           "It works with design tokens too: apply a token to all sides or a different one to each. Inspect shows the value or token applied to every side, matching how CSS borders behave in code."]]

         [:div {:class (stl/css :navigation)}
          [:> c/navigation-bullets*
           {:slide slide
            :navigate navigate
            :total 5}]

          [:> button* {:class (stl/css :next-btn)
                       :on-click next
                       :variant "primary"}
           "Continue"]]]]]]

     1
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.19-shortcuts.jpg"
               :class (stl/css :start-image)
               :border "0"
               :alt "Shortcuts that fit your hands"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "Shortcuts that fit your hands"]]

         [:div {:class (stl/css :feature)}
          [:p {:class (stl/css :feature-content)}
           "You can now reassign any keyboard shortcut in Penpot. Search by action or by key combination, record a new binding, and Penpot warns you if it is already taken. Reset one shortcut or all of them whenever you want."]

          [:p {:class (stl/css :feature-content)}
           "Great news if you type on AZERTY, QWERTZ, or any other keyboard layout, and for anyone who wants Penpot to match their muscle memory. You can also export and import your setup to take it with you."]]

         [:div {:class (stl/css :navigation)}
          [:> c/navigation-bullets*
           {:slide slide
            :navigate navigate
            :total 5}]

          [:> button* {:class (stl/css :next-btn)
                       :on-click next
                       :variant "primary"}
           "Continue"]]]]]]

     2
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.19-paths.jpg"
               :class (stl/css :start-image)
               :border "0"
               :alt "Drawing and path editing, refreshed"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "Drawing and path editing, refreshed"]]

         [:div {:class (stl/css :feature)}
          [:p {:class (stl/css :feature-content)}
           "The path editor gets a full refresh: smoother freehand curves that keep sharp corners sharp, the path toolbar available while you draw, node snapping on by default, and the ability to continue drawing from existing nodes and segments. Hold Shift to snap angles in 15° steps."]

          [:p {:class (stl/css :feature-content)}
           "The result: everything you need to draw your own icons and bring any illustration you can imagine to life, without leaving Penpot."]]

         [:div {:class (stl/css :navigation)}
          [:> c/navigation-bullets*
           {:slide slide
            :navigate navigate
            :total 5}]

          [:> button* {:class (stl/css :next-btn)
                       :on-click next
                       :variant "primary"}
           "Continue"]]]]]]

     3
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.19-tokens.jpg"
               :class (stl/css :start-image)
               :border "0"
               :alt "Design tokens, shared from a library"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "Design tokens, shared from a library"]]

         [:div {:class (stl/css :feature)}
          [:p {:class (stl/css :feature-content)}
           "Keep your tokens in one library and use them in every file that links it. Tokens stay in sync when the library changes, just like components and colors. In the consuming file you can apply tokens and switch themes, while edits happen in the source library, so your design system stays the single source of truth."]

          [:p {:class (stl/css :feature-content)}
           "The Assets tab shows where your tokens come from."]]

         [:div {:class (stl/css :navigation)}
          [:> c/navigation-bullets*
           {:slide slide
            :navigate navigate
            :total 5}]

          [:> button* {:class (stl/css :next-btn)
                       :on-click next
                       :variant "primary"}
           "Continue"]]]]]]

     4
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.19-performance.jpg"
               :class (stl/css :start-image)
               :border "0"
               :alt "WebGL rendering takes a big step"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "WebGL rendering takes a big step"]]

         [:div {:class (stl/css :feature)}
          [:p {:class (stl/css :feature-content)}
           "WebGL rendering gets one of its biggest upgrades yet. Improved tile rendering keeps large files smooth while you move around the canvas, and the new text editor built for WebGL is more precise and much faster. Exports now use the same engine, so they are radically faster and higher quality."]

          [:p {:class (stl/css :feature-content)}
           "If you have not tried it yet, turn it on from user settings. Your feedback keeps shaping it."]]

         [:div {:class (stl/css :navigation)}
          [:> c/navigation-bullets*
           {:slide slide
            :navigate navigate
            :total 5}]

          [:> button* {:class (stl/css :next-btn)
                       :on-click finish
                       :variant "primary"}
           "Let's go"]]]]]])))
