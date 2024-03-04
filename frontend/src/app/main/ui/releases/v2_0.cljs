;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.releases.v2-0
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.ui.releases.common :as c]
   [rumext.v2 :as mf]))

;; TODO: Review all copies and alt text
(defmethod c/render-release-notes "2.0"
  [{:keys [slide klass next finish navigate version]}]
  (mf/html
   (case slide
     :start
     [:div {:class (stl/css :modal-overlay)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        ;; TODO: Review alt
        [:img {:src "images/features/2.0-intro-image.png"
               :class (stl/css :start-image)
               :border "0"
               :alt "Community code contributions"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "What's new?"]

          [:div {:class (stl/css :verstion-tag)}
           (dm/str "Version " version)]]

         [:div {:class (stl/css :features-block)}
          [:div {:class (stl/css :feature)}
           [:h2 {:class (stl/css :feature-title)}
            "CSS Grid Layout"]
           [:p  {:class (stl/css :feature-content)}
            "Crea una estructura flexible para componer
             los elementos de tu diseño y obten el código html/css."]]

          [:div {:class (stl/css :feature)}
           [:h2 {:class (stl/css :feature-title)}
            "New Components"]
           [:p  {:class (stl/css :feature-content)}
            "Ahora tus main components estarán en un espacio
             físico, para que los puedas ver y gestionar fácilmente."]]

          [:div {:class (stl/css :feature)}
           [:h2 {:class (stl/css :feature-title)}
            "New User Interface"]
           [:p  {:class (stl/css :feature-content)}
            "Hemos hecho Penpot aún más bonito, y además
             ahora puedes elegir entre tema oscuro y claro."]]]

         [:div {:class (stl/css :navigation)}
          [:button {:class (stl/css :next-btn)
                    :on-click next} "Continue"]]]]]]

     0
     [:div {:class (stl/css :modal-overlay)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        ;; TODO: Review alt
        [:img {:src "images/features/2.0-css-grid.gif"
               :class (stl/css :start-image)
               :border "0"
               :alt "Community code contributions"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "css grid layout"]]
         [:div {:class (stl/css :feature)}
          [:p {:class (stl/css :feature-content)}
           "¿Querías más flexibilidad para componer tus diseños?
            Selecciona GridLayout para crear una estructura con los
            márgenes y espacios que necesites. Los elementos de tu diseño
            se adaptarán como un guante. Además tendrás en el momento el
            código html y css con estándares web."]

          [:p {:class (stl/css :feature-content)}
           "Elige entre FlexLayout o GridLayout en tu panel lateral derecho."]]

         [:div {:class (stl/css :navigation)}
          [:& c/navigation-bullets
           {:slide slide
            :navigate navigate
            :total 3}]

          [:button {:on-click next
                    :class (stl/css :next-btn)} "Continue"]]]]]]

     1
     [:div {:class (stl/css :modal-overlay)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.0-components.gif"
               :class (stl/css :start-image)
               :border "0"
               :alt "Community code contributions"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "New components"]]
         [:div {:class (stl/css :feature)}
          [:p {:class (stl/css :feature-content)}
           "Os hemos escuchado y ahora los main components están
            disponibles en el archivo para gestionarlos más cómodamente."]
          [:p {:class (stl/css :feature-content)}
           "No te preocupes por tus archivos con main componentes v1,
            al abrirlos con la nueva versión los encontrarás agrupados
            en una página nueva, sanos y salvos."]]

         [:div {:class (stl/css :navigation)}

          [:& c/navigation-bullets
           {:slide slide
            :navigate navigate
            :total 3}]

          [:button {:on-click next
                    :class (stl/css :next-btn)} "Continue"]]]]]]

     2
     [:div {:class (stl/css :modal-overlay)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.0-new-ui.gif"
               :class (stl/css :start-image)
               :border "0"
               :alt "Community code contributions"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "REDISEÑO Y MEJORAS DE RENDIMIENTO"]]

         [:div {:class (stl/css :feature)}
          [:p {:class (stl/css :feature-content)}
           "Le hemos dado una vuelta al interface y añadido
            pequeñas mejoras de usabilidad.
            Además, ahora puedes elegir entre tema oscuro y tema claro,
            dignos de Dark Vader y Luke Skywalker."]
          [:p {:class (stl/css :feature-content)}
           "Aunque siempre estamos puliendo el rendimiento
            y la estabilidad, en esta versión hemos
            conseguido grandes mejoras en ese sentido."]

          [:p {:class (stl/css :feature-content)}
           "Que lo disfrutes!"]]

         [:div {:class (stl/css :navigation)}

          [:& c/navigation-bullets
           {:slide slide
            :navigate navigate
            :total 3}]

          [:button {:on-click finish
                    :class (stl/css :next-btn)} "Let's go"]]]]]])))

