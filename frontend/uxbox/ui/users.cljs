(ns uxbox.ui.users
  (:require [rum.core :as rum]
            [uxbox.router :as r]
            ;; [uxbox.users.queries :as q]
            ;; [uxbox.ui.mixins :as mx]
            [uxbox.ui.icons :as icons]
            [uxbox.ui.navigation :as nav]))

(rum/defc user-menu < rum/static
  [open?]
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
       [:span "Save & Exit"]]])

;; FIXME this is a temporal
(def current-user (atom {:user/fullname "Cirilla"
                         :user/avatar "http://lorempixel.com/50/50/"}))
(def menu-open? (atom false))

(rum/defcs user < (rum/local false :menu-open?)
  [own]
  (let [usr @current-user]
    [:div.user-zone {:on-mouse-enter #(reset! menu-open? true)
                     :on-mouse-leave #(reset! menu-open? false)}
     [:span (:user/fullname usr)]
     [:img {:border "0"
            :src (:user/avatar usr)}]
     (user-menu @menu-open?)]))

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
     :on-click #(r/go :main/dashboard)}]
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
     :on-click #(r/go :main/dashboard)}]
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
     :on-click #(r/go :main/dashboard)}]
   [:div.login-links
    [:a {:on-click #(r/go :auth/recover-password)} "Forgot your password?"]
    [:a {:on-click #(r/go :auth/register)} "Don't have an account?"]]])

(rum/defc login
  []
  [:div.login
    [:div.login-body
     [:a icons/logo]
     (login-form)]])
