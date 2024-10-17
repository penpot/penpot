;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.comments
  (:require
   [app.common.geom.point :as gpt]
   [app.common.record :as crc]
   [app.common.spec :as us]
   [app.main.data.comments :as dc]
   [app.main.data.workspace.comments :as dwc]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.plugins.format :as format]
   [app.plugins.parser :as parser]
   [app.plugins.register :as r]
   [app.plugins.shape :as shape]
   [app.plugins.user :as user]
   [app.plugins.utils :as u]
   [beicon.v2.core :as rx]
   [promesa.core :as p]))

(deftype CommentProxy [$plugin $file $page $thread $id]
  Object
  (remove [_]
    (p/create
     (fn [resolve reject]
       (cond
         (not (r/check-permission $plugin "comment:write"))
         (do
           (u/display-not-valid :remove "Plugin doesn't have 'comment:write' permission")
           (reject "Plugin doesn't have 'comment:write' permission"))

         :else
         (->> (rp/cmd! :delete-comment {:id $id})
              (rx/tap #(st/emit! (dc/retrieve-comment-threads $file)))
              (rx/subs! #(resolve) reject)))))))

(defn comment-proxy? [p]
  (instance? CommentProxy p))

(defn comment-proxy
  [plugin-id file-id page-id thread-id users data]
  (let [data* (atom data)]
    (crc/add-properties!
     (CommentProxy. plugin-id file-id page-id thread-id (:id data))
     {:name "$plugin" :enumerable false :get (constantly plugin-id)}
     {:name "$file" :enumerable false :get (constantly file-id)}
     {:name "$page" :enumerable false :get (constantly page-id)}
     {:name "$thread" :enumerable false :get (constantly thread-id)}
     {:name "$id" :enumerable false :get (constantly (:id data))}

     {:name "user" :get (fn [_] (user/user-proxy plugin-id (get users (:owner-id data))))}
     {:name "date" :get (fn [_] (:created-at data))}

     {:name "content"
      :get (fn [_] (:content @data*))
      :set
      (fn [_ content]
        (let [profile (:profile @st/state)]
          (cond
            (or (not (string? content)) (empty? content))
            (u/display-not-valid :content "Not valid")

            (not= (:id profile) (:owner-id data))
            (u/display-not-valid :content "Cannot change content from another user's comments")

            (not (r/check-permission plugin-id "comment:write"))
            (u/display-not-valid :content "Plugin doesn't have 'comment:write' permission")

            :else
            (->> (rp/cmd! :update-comment {:id (:id data) :content content})
                 (rx/tap #(st/emit! (dc/retrieve-comment-threads file-id)))
                 (rx/subs! #(swap! data* assoc :content content))))))})))

(deftype CommentThreadProxy [$plugin $file $page $users $id owner]
  Object
  (findComments
    [_]
    (p/create
     (fn [resolve reject]
       (cond
         (not (r/check-permission $plugin "comment:read"))
         (do
           (u/display-not-valid :findComments "Plugin doesn't have 'comment:read' permission")
           (reject "Plugin doesn't have 'comment:read' permission"))

         :else
         (->> (rp/cmd! :get-comments {:thread-id $id})
              (rx/subs!
               (fn [comments]
                 (resolve
                  (format/format-array
                   #(comment-proxy $plugin $file $page $id $users %) comments)))
               reject))))))

  (reply
    [_ content]
    (cond
      (not (r/check-permission $plugin "comment:write"))
      (u/display-not-valid :reply "Plugin doesn't have 'comment:write' permission")

      (or (not (string? content)) (empty? content))
      (u/display-not-valid :reply "Not valid")

      :else
      (p/create
       (fn [resolve reject]
         (->> (rp/cmd! :create-comment {:thread-id $id :content content})
              (rx/subs! #(resolve (comment-proxy $plugin $file $page $id $users %)) reject))))))

  (remove [_]
    (let [profile (:profile @st/state)]
      (cond
        (not (r/check-permission $plugin "comment:write"))
        (u/display-not-valid :remove "Plugin doesn't have 'comment:write' permission")

        (not= (:id profile) owner)
        (u/display-not-valid :remove "Cannot change content from another user's comments")

        :else
        (p/create
         (fn [resolve]
           (p/create
            (st/emit! (dc/delete-comment-thread-on-workspace {:id $id} #(resolve))))))))))

(defn comment-thread-proxy? [p]
  (instance? CommentThreadProxy p))

(defn comment-thread-proxy
  [plugin-id file-id page-id users data]
  (let [data* (atom data)]
    (crc/add-properties!
     (CommentThreadProxy. plugin-id file-id page-id users (:id data) (:owner-id data))
     {:name "$plugin" :enumerable false :get (constantly plugin-id)}
     {:name "$file" :enumerable false :get (constantly file-id)}
     {:name "$page" :enumerable false :get (constantly page-id)}
     {:name "$id" :enumerable false :get (constantly (:id data))}
     {:name "$users" :enumerable false :get (constantly users)}
     {:name "page" :enumerable false :get (fn [_] (u/locate-page file-id page-id))}

     {:name "seqNumber" :get (fn [_] (:seqn data))}
     {:name "owner" :get (fn [_] (user/user-proxy plugin-id (get users (:owner-id data))))}
     {:name "board" :get (fn [_] (shape/shape-proxy plugin-id file-id page-id (:frame-id data)))}

     {:name "position"
      :get (fn [_] (format/format-point (:position @data*)))
      :set
      (fn [_ position]
        (let [position (parser/parse-point position)]
          (cond
            (or (not (us/safe-number? (:x position))) (not (us/safe-number? (:y position))))
            (u/display-not-valid :position "Not valid point")

            (not (r/check-permission plugin-id "comment:write"))
            (u/display-not-valid :position "Plugin doesn't have 'comment:write' permission")

            :else
            (do (st/emit! (dwc/update-comment-thread-position @data* [(:x position) (:y position)]))
                (swap! data* assoc :position (gpt/point position))))))}

     {:name "resolved"
      :get (fn [_] (:is-resolved @data*))
      :set
      (fn [_ is-resolved]
        (cond
          (not (boolean? is-resolved))
          (u/display-not-valid :resolved "Not a boolean type")

          (not (r/check-permission plugin-id "comment:write"))
          (u/display-not-valid :resolved "Plugin doesn't have 'comment:write' permission")

          :else
          (do (st/emit! (dc/update-comment-thread (assoc @data* :is-resolved is-resolved)))
              (swap! data* assoc :is-resolved is-resolved))))})))

