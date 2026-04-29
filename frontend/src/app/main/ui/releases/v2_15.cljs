;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.releases.v2-15
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.ui.releases.common :as c]
   [rumext.v2 :as mf]))

(defmethod c/render-release-notes "2.15"
  [{:keys [slide klass next finish navigate version]}]
  (mf/html
   (case slide
     :start
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.15-slide-0.jpg"
               :class (stl/css :start-image)
               :border "0"
               :alt "Penpot 2.15 is here!"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "What’s new in Penpot?"]

          [:div {:class (stl/css :version-tag)}
           (dm/str "Version " version)]]

         [:div {:class (stl/css :features-block)}
          [:span {:class (stl/css :feature-title)}
           "One major feature: the Penpot MCP Server, with infinite workflow possibilities"]

          [:p  {:class (stl/css :feature-content)}
           "This release marks a major MCP milestone: Penpot MCP moves from an early technical setup to an accessible in-app experience via hosted remote setup. Whether you already know MCP or are new to it, it's now zero-friction to connect your AI client and turn prompts into real actions on real design data."]

          [:p  {:class (stl/css :feature-content)}
           "With 2.15, we are opening the door to truly multi-directional workflows between design and code, while staying faithful to Penpot values: openness, freedom of choice, and respect for your data."]

          [:p  {:class (stl/css :feature-content)}
           "Let’s dive in!"]]

         [:div {:class (stl/css :navigation)}
          [:button {:class (stl/css :next-btn)
                    :on-click next} "Continue"]]]]]]

     0
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.15-mcp-01.gif"
               :class (stl/css :start-image)
               :border "0"
               :alt "Penpot MCP Server: AI connected to real design context"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "Penpot MCP Server: AI connected to real design context"]]

         [:div {:class (stl/css :feature)}
          [:p {:class (stl/css :feature-content)}
           "Penpot MCP Server is the bridge between your AI client and your Penpot file. You describe what you need in natural language, your agent picks the right operation, and MCP translates that into real actions through Penpot APIs."]

          [:p {:class (stl/css :feature-content)}
           "This is not a generic 'describe and generate' flow. It is context-aware work with components, tokens, pages, layers, and structure. In short: design expressed as code, now usable through your preferred AI assistant."]

          [:p {:class (stl/css :feature-content)}
           "You can run MCP in two ways. Remote MCP is hosted and simpler to set up. Local MCP runs on your machine and gives advanced teams extra control. Same vision, different operating model."]]

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
        [:img {:src "images/features/2.15-mcp-02.gif"
               :class (stl/css :start-image)
               :border "0"
               :alt "Multi-directional workflows, from design to code and back"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "Multi-directional workflows, from design to code and back"]]

         [:div {:class (stl/css :feature)}
          [:p {:class (stl/css :feature-content)}
           "The biggest unlock in 2.15 is multi-directionality. You can move from design to code, and from code back to design, without losing intent or structure in the process."]

          [:p {:class (stl/css :feature-content)}
           "• Generate semantic HTML/CSS from real layouts."]
          [:p {:class (stl/css :feature-content)}
           "• Translate tokens and styles into code variables."]
          [:p {:class (stl/css :feature-content)}
           "• Export only assets in use."]
          [:p {:class (stl/css :feature-content)}
           "• Validate design-code consistency."]
          [:p {:class (stl/css :feature-content)}
           "• Reorganize layers, apply naming rules, and automate repetitive design system maintenance."]

          [:p {:class (stl/css :feature-content)}
           "This is where MCP becomes workflow infrastructure. Less manual glue work, fewer handoff gaps, and faster iterations between designers and developers."]]

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
        [:img {:src "images/features/2.15-mcp-03.gif"
               :class (stl/css :start-image)
               :border "0"
               :alt "Your stack, your model, your control"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "Your stack, your model, your control"]]

         [:div {:class (stl/css :feature)}
          [:p {:class (stl/css :feature-content)}
           "With MCP, you connect Penpot to the AI client and model you already trust. Cursor, Claude, VS Code, Codex, or another MCP-compatible setup: the workflow adapts to your stack, not the other way around."]

          [:p {:class (stl/css :feature-content)}
           "You can run it hosted for a faster setup, or locally when you need tighter infrastructure control. The same applies to data boundaries: Penpot provides the bridge to your design context, while your team decides how and where AI runs."]

          [:p {:class (stl/css :feature-content)}
           "In practice, this means teams can automate design and code workflows without giving up tool freedom, deployment control, or ownership of their process.
"]]
         [:div {:class (stl/css :navigation)}

          [:& c/navigation-bullets
           {:slide slide
            :navigate navigate
            :total 3}]

          [:button {:on-click finish
                    :class (stl/css :next-btn)} "Let's go"]]]]]])))

