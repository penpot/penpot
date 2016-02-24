(ns uxbox.ui.users
  (:require [sablono.core :as html :refer-macros [html]]
            [cats.labs.lens :as l]
            [rum.core :as rum]
            [uxbox.router :as r]
            [uxbox.rstore :as rs]
            [uxbox.state :as s]
            [uxbox.data.auth :as da]
            [uxbox.ui.icons :as i]
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
     i/page
     [:span "Page settings"]]
    [:li
     i/grid
     [:span "Grid settings"]]
    [:li
     i/eye
     [:span "Preview"]]
    [:li
     i/user
     [:span "Your account"]]
    [:li {:on-click #(rs/emit! (da/logout))}
     i/exit
     [:span "Exit"]]]))

(def user-menu
  (mx/component
   {:render menu-render
    :name "user-menu"
    :mixins []}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; User Widget
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:static user-l
  (as-> (l/in [:auth]) $
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
             :src (:photo user)}]
      (user-menu (:open @local))])))

(def user
  (mx/component
   {:render user-render
    :name "user"
    :mixins [rum/reactive (rum/local {:open false})]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Register
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; (rum/defc register-form < rum/static
;;   []
;;   [:div.login-content
;;    [:input.input-text
;;      {:name "name"
;;       :placeholder "Name"
;;       :type "text"}]
;;    [:input.input-text
;;      {:name "email"
;;       :placeholder "Email"
;;       :type "email"}]
;;    [:input.input-text
;;     {:name "password"
;;      :placeholder "Password"
;;      :type "password"}]
;;    [:input.btn-primary
;;     {:name "login"
;;      :value "Continue"
;;      :type "submit"
;;      :on-click #(r/go :dashboard/projects)}]
;;    [:div.login-links
;;     [:a
;;      {:on-click #(r/go :auth/login)}
;;      "You already have an account?"]]])

;; (rum/defc register < rum/static
;;   []
;;   [:div.login
;;    [:div.login-body
;;     [:a i/logo]
;;     (register-form)]])

;; (rum/defc recover-password-form < rum/static
;;   []
;;   [:div.login-content
;;    [:input.input-text
;;      {:name "email"
;;       :placeholder "Email"
;;       :type "email"}]
;;    [:input.btn-primary
;;     {:name "login"
;;      :value "Continue"
;;      :type "submit"
;;      :on-click #(r/go :dashboard/projects)}]
;;    [:div.login-links
;;     [:a
;;      {:on-click #(r/go :auth/login)}
;;      "You have remembered your password?"]
;;     [:a
;;      {:on-click #(r/go :auth/register)}
;;      "Don't have an account?"]]])

;; (rum/defc recover-password < rum/static
;;   []
;;   [:div.login
;;     [:div.login-body
;;      [:a i/logo]
;;      (recover-password-form)]])

