(ns uxbox.ui.users
  (:require [sablono.core :as html :refer-macros [html]]
            [cats.labs.lens :as l]
            [rum.core :as rum]
            [uxbox.router :as r]
            [uxbox.state :as s]
            [uxbox.ui.icons :as icons]
            [uxbox.ui.navigation :as nav]
            [uxbox.ui.mixins :as mx]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Menu
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn menu-render
  [own open?]
  (html
   [:ul.dropdown {:class (when-not open?
                           "hide")}
    [:li
     icons/page
     [:span "Page settings"]]
    [:li
     icons/grid
     [:span "Grid settings"]]
    [:li
     icons/eye
     [:span "Preview"]]
    [:li
     icons/user
     [:span "Your account"]]
    [:li
     icons/exit
     [:span "Save & Exit"]]]))

(def user-menu
  (mx/component
   {:render menu-render
    :name "user-menu"
    :mixins []}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; User Widget
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; FIXME this is a temporal
(def current-user (atom {:user/fullname "Cirilla"
                         :user/avatar "//lorempixel.com/50/50/"}))
(def menu-open? (atom false))

(def ^:static user-l
  (as-> (l/in [:user]) $
    (l/focus-atom $ s/state)))

(defn user-render
  [own]
  (let [user (rum/react user-l)
        local (:rum/local own)]
    (html
     [:div.user-zone {:on-mouse-enter #(swap! local assoc :open true)
                      :on-mouse-leave #(swap! local assoc :open false)}
      [:span (:fullname user)]
      [:img {:border "0"
             :src (:avatar user)}]
      (user-menu (:open @local))])))

(def user
  (mx/component
   {:render user-render
    :name "user"
    :mixins [rum/reactive (rum/local {:open false})]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Register
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(rum/defc register-form < rum/static
  []
  [:div.login-content
   [:input.input-text
     {:name "name"
      :placeholder "Name"
      :type "text"}]
   [:input.input-text
     {:name "email"
      :placeholder "Email"
      :type "email"}]
   [:input.input-text
    {:name "password"
     :placeholder "Password"
     :type "password"}]
   [:input.btn-primary
    {:name "login"
     :value "Continue"
     :type "submit"
     :on-click #(r/go :dashboard/projects)}]
   [:div.login-links
    [:a
     {:on-click #(r/go :auth/login)}
     "You already have an account?"]]])

(rum/defc register < rum/static
  []
  [:div.login
   [:div.login-body
    [:a icons/logo]
    (register-form)]])

(rum/defc recover-password-form < rum/static
  []
  [:div.login-content
   [:input.input-text
     {:name "email"
      :placeholder "Email"
      :type "email"}]
   [:input.btn-primary
    {:name "login"
     :value "Continue"
     :type "submit"
     :on-click #(r/go :dashboard/projects)}]
   [:div.login-links
    [:a
     {:on-click #(r/go :auth/login)}
     "You have remembered your password?"]
    [:a
     {:on-click #(r/go :auth/register)}
     "Don't have an account?"]]])

(rum/defc recover-password < rum/static
  []
  [:div.login
    [:div.login-body
     [:a icons/logo]
     (recover-password-form)]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Login
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(rum/defc login-form < rum/static
  []
  [:div.login-content
   [:input.input-text
     {:name "email"
      :placeholder "Email"
      :type "email"}]
   [:input.input-text
    {:name "password"
     :placeholder "Password"
     :type "password"}]
   [:div.input-checkbox.check-primary
    [:input#checkbox1 {:value "1"
                       :type "checkbox"}]
    [:label {:for "checkbox1"} "Keep Me Signed in"]]
   [:input.btn-primary
    {:name "login"
     :value "Continue"
     :type "submit"
     :on-click #(r/go :dashboard/projects)}]
   [:div.login-links
    [:a {:on-click #(r/go :auth/recover-password)} "Forgot your password?"]
    [:a {:on-click #(r/go :auth/register)} "Don't have an account?"]]])

(rum/defc login
  []
  [:div.login
    [:div.login-body
     [:a icons/logo]
     (login-form)]])
