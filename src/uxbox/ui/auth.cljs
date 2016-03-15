(ns uxbox.ui.auth
  (:require [sablono.core :as html :refer-macros [html]]
            [lentes.core :as l]
            [cuerdas.core :as str]
            [rum.core :as rum]
            [uxbox.router :as r]
            [uxbox.state :as s]
            [uxbox.rstore :as rs]
            [uxbox.data.auth :as da]
            [uxbox.util.dom :as dom]
            [uxbox.ui.icons :as i]
            [uxbox.ui.messages :as uum]
            [uxbox.ui.navigation :as nav]
            [uxbox.ui.mixins :as mx]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Login
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- login-submit
  [local]
  (let [form (:form @local)]
    (rs/emit! (da/login {:username (:email form)
                         :password (:password form)}))))

(defn- login-submit-enabled?
  [local]
  (let [form (:form @local)]
    (and (not (str/empty? (:email form "")))
         (not (str/empty? (:password form ""))))))

(defn- login-field-change
  [local field event]
  (let [value (str/trim (dom/event->value event))]
    (println @local value)
    (swap! local assoc-in [:form field] value)))

(defn- login-render
  [own local]
  (let [on-submit #(login-submit local)
        submit-enabled? (login-submit-enabled? local)
        form (:form @local)]
    (println "submit-enabled?" submit-enabled?)
    (html
     [:div.login
      [:div.login-body
       (uum/messages)
       [:a i/logo]
       [:div.login-content
        [:input.input-text
         {:name "email"
          :ref "email"
          :value (:email form "")
          :on-change #(login-field-change local :email %)
          :placeholder "Email or Username"
          :type "text"}]
        [:input.input-text
         {:name "password"
          :ref "password"
          :value (:password form "")
          :on-change #(login-field-change local :password %)
          :placeholder "Password"
          :type "password"}]
        #_[:div.input-checkbox.check-primary
         [:input#checkbox1 {:value "1"
                            :type "checkbox"}]
         [:label {:for "checkbox1"} "Keep Me Signed in"]]
        [:input.btn-primary
         {:name "login"
          :class (when-not submit-enabled? "btn-disabled")
          :disabled (not submit-enabled?)
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
