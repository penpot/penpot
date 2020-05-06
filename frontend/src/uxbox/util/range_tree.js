/* 
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * This Source Code Form is "Incompatible With Secondary Licenses", as
 * defined by the Mozilla Public License, v. 2.0.
 *
 * Copyright (c) 2020 UXBOX Labs SL
 */

"use strict";

goog.provide("uxbox.util.range_tree");
goog.require("cljs.core")

goog.scope(function() {
    const eq = cljs.core._EQ_;
    const vec = cljs.core.vec;
    const nil = cljs.core.nil;
     
    class Node {
        constructor(value, data) {
            this.value = value;
            this.data = [ data ];
            this.left = null;
            this.right = null;
        }
    }
    
    // Will store a map from key to list of data
    //   value => [ data ]
    // The values can be queried in range and the data stored will be retrived whole
    // but can be removed/updated individually using clojurescript equality
    class RangeTree {
        constructor() {
            this.root = null;
        }
     
        insert(value, data) {
            this.root = recInsert(this.root, value, data);
            return this;
        }
     
        remove(value, data) {
            this.root = recRemove(this.root, value, data);
            return this;
        }
    
        update (value, oldData, newData) {
            this.root = recUpdate(this.root, value, oldData, newData);
            return this;
        }
    
        get(value) {
            return recGet(this.root, value);
        }
    
        rangeQuery (fromValue, toValue) {
            return recRangeQuery(this.root, fromValue, toValue, []);
        }
    }

    // Tree implementation functions

    // Insert recursively in the tree
    function recInsert (branch, value, data) {
        if (branch === null) {
            return new Node(value, data);
        } else if (branch.value === value) {
            // Find node we'll add to the end of the list
            branch.data.push(data);
        } else if (branch.value > value) {
            // Target value is less than the current value we go left
            branch.left = recInsert(branch.left, value, data);
        } else if (branch.value < value) {
            branch.right = recInsert(branch.right, value, data);
        }
        return branch;
    }

    // Search for the min node
    function searchMin(branch) {
        if (branch.left === null) {
            return branch;
        } else {
            return searchMin(branch.left);
        }
    }

    // Remove the lefmost node of the current branch
    function recRemoveMin(branch) {
        if (branch.left === null) {
            return branch.right;
        } else {
            branch.left = recRemoveMin(branch.left);
            return branch;
        }
    }

    // Remove the data element for the value given
    function recRemove(branch, value, data) {
        if (branch === null) {
            // Not found
            return branch;
        } else if (branch.value === value) {
            // Node found, we remove the data
            branch.data = branch.data.filter ((it) => !eq(it, data));

            if (branch.data.length > 0) {
                return branch;
            }

            // If the data is empty we need to remove the branch
            if (branch.right === null) {
                return branch.left;
            } else if (branch.left === null) {
                return branch.right;
            } else {
                const oldBranch = branch;
                const newBranch = searchMin(branch.right);
                newBranch.right = recRemoveMin(oldBranch.right);
                newBranch.left = oldBranch.left;
                return newBranch;
            }
        } else if (branch.value > value) {
            // Target value is less than the current value we go left
            branch.left = recRemove (branch.left, value, data);
            return branch;
        } else if (branch.value < value) {
            branch.right = recRemove (branch.right, value, data);
            return branch;
        }
    }

    // Retrieve all the data related to value
    function recGet(branch, value) {
        if (branch === null) {
            return null;
        } else if (branch.value === value) {
            return branch.data;
        } else if (branch.value > value) {
            return recGet(branch.left, value);
        } else if (branch.value < value) {
            return recGet(branch.right, value);
        }
    }
    
    function recUpdate(branch, value, oldData, newData) {
        if (branch === null) {
            return branch;
        } else if (branch.value === value) {
            branch.data = branch.data.map((it) => (eq(it, oldData)) ? newData : it);
            return branch;
        } else if (branch.value > value) {
            return recUpdate(branch.left, value, oldData, newData);
        } else if (branch.value < value) {
            return recUpdate(branch.right, value, oldData, newData);
        }
    }

    function recRangeQuery(branch, fromValue, toValue, result) {
        if (branch === null) {
            return result;
        }
        if (fromValue < branch.value) {
            recRangeQuery(branch.left, fromValue, toValue, result);
        }
        if (fromValue <= branch.value && toValue >= branch.value) {
            Array.prototype.push.apply(result, branch.data);
        }
        if (toValue > branch.value) {
            recRangeQuery(branch.right, fromValue, toValue, result);
        }
        return result;
    }

    // External API to CLJS
    const self = uxbox.util.range_tree;
    self.make_tree = () => new RangeTree();
    self.insert = (tree, value, data) => tree.insert(value, data);
    self.remove = (tree, value, data) => tree.remove(value, data);
    self.update = (tree, value, oldData, newData) => tree.update(value, oldData, newData);
    self.get = (tree, value) => {
        const result = tree.get(value);
        if (!result) {
            return nil;
        }
        return vec(result);
    };
    self.range_query = (tree, from_value, to_value) => vec(tree.rangeQuery(from_value, to_value));
});
