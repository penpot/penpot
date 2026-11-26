;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.releases.v2-20
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.releases.common :as c]
   [rumext.v2 :as mf]))

(defmethod c/render-release-notes "2.20"
  [{:keys [slide klass next finish navigate version]}]
  (mf/html
   (case slide
     :start
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.20-slide-0.jpg"
               :class (stl/css :start-image)
               :border "0"
               :alt "Penpot 2.20 is here!"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "What’s new in Penpot?"]

          [:div {:class (stl/css :version-tag)}
           (dm/str "Version " version)]]

         [:div {:class (stl/css :features-block)}

          [:p  {:class (stl/css :feature-content)}
           "Penpot 2.20 gives the MCP server a big step up (multiple files, dashboard access and OAuth) and makes WebGL rendering the default for new accounts. Design tokens get smoother and easier to reach from your pipeline, and saving large files is faster and much harder to break."]

          [:p  {:class (stl/css :feature-content)}
           "As always, the release also brings quality-of-life upgrades, bug fixes, performance improvements and security fixes."]]

         [:div {:class (stl/css :navigation)}
          [:> button* {:class (stl/css :next-btn)
                       :on-click next
                       :variant "primary"}
           "Continue"]]]]]]

     0
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.20-MCP.jpg"
               :class (stl/css :start-image)
               :border "0"
               :alt "Multiple concurrent agents anywhere on Penpot"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "Multiple concurrent agents anywhere on Penpot"]]

         [:div {:class (stl/css :feature)}
          [:p {:class (stl/css :feature-content)}
           "The Penpot MCP server takes its biggest leap yet. Your AI agent is no longer tied to one open file:"]

          [:ul {:class (stl/css :feature-list)}
           [:li "Several files at once: keep your design system, product files and libraries connected together, and let the agent move between them."]
           [:li "Your whole dashboard: the agent can find, open and organize your teams, projects and files."]
           [:li "Connect in a few clicks: MCP clients connect with OAuth, so you just approve access in Penpot."]]

          [:p {:class (stl/css :feature-content)}
           "Something not working as expected? The MCP server now has its own feedback channel, so your agent can report issues straight to us."]]

         [:div {:class (stl/css :navigation)}
          [:> c/navigation-bullets*
           {:slide slide
            :navigate navigate
            :total 4}]

          [:> button* {:class (stl/css :next-btn)
                       :on-click next
                       :variant "primary"}
           "Continue"]]]]]]

     1
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.20-webgl.jpg"
               :class (stl/css :start-image)
               :border "0"
               :alt "A faster Penpot out of the box"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "A faster Penpot out of the box"]]

         [:div {:class (stl/css :feature)}
          [:p {:class (stl/css :feature-content)}
           "New accounts now start with WebGL rendering switched on. It’s faster on complex files, and exporting shapes and boards no longer freezes the interface while it works."]

          [:p {:class (stl/css :feature-content)}
           "If you haven’t already, give WebGL rendering a try from your settings and tell us how it goes. Your feedback is what gets it ready for everyone."]]

         [:div {:class (stl/css :navigation)}
          [:> c/navigation-bullets*
           {:slide slide
            :navigate navigate
            :total 4}]

          [:> button* {:class (stl/css :next-btn)
                       :on-click next
                       :variant "primary"}
           "Continue"]]]]]]

     2
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.20-tokens.jpg"
               :class (stl/css :start-image)
               :border "0"
               :alt "Smoother token workflows"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "Smoother token workflows"]]

         [:div {:class (stl/css :feature)}
          [:p {:class (stl/css :feature-content)}
           "Typography tokens that reference another token can now be detached: Penpot copies the referenced values into the token so you can tweak them on their own. Open the color tokens tab in the color picker and the search field is ready to type."]

          [:p {:class (stl/css :feature-content)}
           "Developers can now read a file’s design tokens straight from the API, ready to feed your CI pipeline."]]

         [:div {:class (stl/css :navigation)}
          [:> c/navigation-bullets*
           {:slide slide
            :navigate navigate
            :total 4}]

          [:> button* {:class (stl/css :next-btn)
                       :on-click next
                       :variant "primary"}
           "Continue"]]]]]]

     3
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.20-safer.jpg"
               :class (stl/css :start-image)
               :border "0"
               :alt "Safer saves, smoother days"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "Safer saves, smoother days"]]

         [:div {:class (stl/css :feature)}
          [:p {:class (stl/css :feature-content)}
           "Saving large files is faster, saves retry automatically after a hiccup, and if a file can’t be saved Penpot switches to read-only so no edits get lost. Imports and exports run in the background with progress for each file. Text pasted inside Penpot keeps its full formatting, Ctrl/Cmd+click opens dashboard files in a new tab, and self-hosted teams can now serve Penpot under a subpath."]

          [:p {:class (stl/css :feature-content)}
           "Plus plenty of bug fixes and performance improvements, with help from community contributors like @ShreyashAgare26, @filipsajdak, @GitNimay, @lukel97, @desty and many more. Thank you!"]]

         [:div {:class (stl/css :navigation)}
          [:> c/navigation-bullets*
           {:slide slide
            :navigate navigate
            :total 4}]

          [:> button* {:class (stl/css :next-btn)
                       :on-click finish
                       :variant "primary"}
           "Let's go"]]]]]])))
