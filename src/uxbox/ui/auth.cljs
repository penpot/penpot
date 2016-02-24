(ns uxbox.ui.auth
  (:require [sablono.core :as html :refer-macros [html]]
            [cats.labs.lens :as l]
            [rum.core :as rum]
            [uxbox.router :as r]
            [uxbox.state :as s]
            [uxbox.rstore :as rs]
            [uxbox.data.auth :as da]
            [uxbox.ui.icons :as i]
            [uxbox.ui.navigation :as nav]
            [uxbox.ui.mixins :as mx]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Login
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- login-render
  [own local]
  (letfn [(on-submit []
            (rs/emit! (da/login {:username "cirilla"
                                 :password "secret"})))]
    (html
     [:div.login
      [:div.login-body
       [:a i/logo]
       [:div.login-content
        [:input.input-text
         {:name "email"
          :placeholder "Email or Username"
          :type "text"}]
        [:input.input-text
         {:name "password"
          :placeholder "Password"
          :type "password"}]
        #_[:div.input-checkbox.check-primary
         [:input#checkbox1 {:value "1"
                            :type "checkbox"}]
         [:label {:for "checkbox1"} "Keep Me Signed in"]]
        [:input.btn-primary
         {:name "login"
          :value "Continue"
          :type "submit"
          :on-click on-submit}]
        [:div.login-links
         [:a {:on-click #(r/go :auth/recover-password)} "Forgot your password?"]
         [:a {:on-click #(r/go :auth/register)} "Don't have an account?"]]]]])))

(def ^:const login
  (mx/component
   {:render #(login-render % (:rum/local %))
    :name "login"
    :mixins [(mx/local)]}))
