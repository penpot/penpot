;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.tokenscript.ast
  "AST nodes and traversal helpers.

  Nodes are plain maps with a `:node` keyword and the originating
  `:token`. Node kinds:

    :bin-op           {:left :op-token :right}
    :unary-op         {:op-token :expr}
    :num              {:value}
    :string           {:value}
    :template-string  {:parts}
    :list             {:elements}
    :implicit-list    {:elements}
    :function-call    {:name :args}
    :reference        {:value}
    :identifier       {:name}
    :hex-color        {:value}
    :boolean          {:value}
    :null             {}
    :element-with-unit {:ast-node :unit}
    :assign           {:var-name :type-decl :assignment-expr}
    :type-decl        {:base-type :sub-types}
    :reassign         {:identifiers :value}
    :return           {:expr}
    :while            {:condition :body}
    :for-each         {:item-var :index-var :collection :body}
    :if-condition     {:condition :body}
    :if               {:conditions :else-body}
    :block            {:statements}
    :statement-list   {:statements}
    :attribute-access {:left :right}
    :no-op            {}

  Tolerant parsing adds partial nodes: :partial-bin-op, :partial-unary-op,
  :partial-function-call, :partial-paren, :partial-reference,
  :partial-string."
  (:require
   [clojure.string :as str]))

(defn node?
  [v]
  (and (map? v) (keyword? (:node v))))

(defn type-decl-name
  "Full type name of a type declaration, e.g. `Color.RGB`."
  [{:keys [base-type sub-types]}]
  (str/join "." (map :name (cons base-type sub-types))))

(defn children
  "Direct child nodes of `node`."
  [node]
  (case (:node node)
    :bin-op [(:left node) (:right node)]
    :unary-op [(:expr node)]
    (:list :implicit-list) (:elements node)
    :function-call (:args node)
    :element-with-unit [(:ast-node node)]
    :assign (when-let [e (:assignment-expr node)] [e])
    :reassign [(:value node)]
    :return [(:expr node)]
    :while [(:condition node) (:body node)]
    :for-each [(:collection node) (:body node)]
    :if-condition [(:condition node) (:body node)]
    :if (concat (:conditions node) (when-let [e (:else-body node)] [e]))
    :block [(:statements node)]
    :statement-list (:statements node)
    :template-string (:parts node)
    :attribute-access (let [r (:right node)]
                        (if (contains? #{:function-call :partial-function-call} (:node r))
                          [(:left node) r]
                          [(:left node)]))
    :partial-bin-op [(:left node)]
    :partial-function-call (:args node)
    :partial-paren [(:expr node)]
    nil))

(defn walk
  "Depth first pre-order traversal calling `f` on every node."
  [node f]
  (when (some? node)
    (f node)
    (doseq [child (children node)]
      (walk child f))))

(defn nodes
  "Lazy pre-order seq of all nodes in the tree."
  [node]
  (when (some? node)
    (cons node (mapcat nodes (children node)))))

(defn filter-nodes
  [node pred]
  (filterv pred (nodes node)))

(defn reference-nodes
  "All reference nodes, optionally only those referencing `target`."
  ([node] (filter-nodes node #(= :reference (:node %))))
  ([node target] (filter-nodes node #(and (= :reference (:node %)) (= target (:value %))))))

(defn last-statement
  [node]
  (if (and (= :statement-list (:node node)) (seq (:statements node)))
    (last (:statements node))
    node))

(defn assignment-info
  "`{:var-name .. :assignment? ..}` when the last statement is a variable
  declaration, otherwise nil."
  [node]
  (let [stmt (last-statement node)]
    (when (= :assign (:node stmt))
      {:var-name (:name (:var-name stmt))
       :assignment? (some? (:assignment-expr stmt))})))

(defn reassignment-info
  [node]
  (let [stmt (last-statement node)]
    (when (= :reassign (:node stmt))
      {:var-name (:name (first (:identifiers stmt)))})))

(defn rename-references
  "Rewrites `original` replacing the references named in `rename-map`
  (`{\"old.name\" \"new.name\"}`) using the token positions of `ast`."
  [original ast rename-map]
  (let [positions (for [[old-name new-name] rename-map
                        ref (reference-nodes ast old-name)
                        :let [tok (:token ref)]
                        :when (and (some? (:pos tok)) (some? (:end-pos tok)))]
                    {:start (:pos tok) :end (:end-pos tok) :new-name new-name})]
    (reduce (fn [result {:keys [start end new-name]}]
              (str (subs result 0 start) new-name (subs result end)))
            original
            (sort-by :start > positions))))
