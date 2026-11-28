//! A least-recently-used map without a capacity of its own: the caller decides
//! what to evict, by count or by bytes, with [`Lru::pop_oldest`]. Lookups,
//! inserts and evictions are O(log n): a `BTreeMap` orders the keys by the tick
//! of their last use.

use std::collections::{BTreeMap, HashMap};
use std::hash::Hash;

pub struct Lru<K, V> {
    tick: u64,
    entries: HashMap<K, (u64, V)>,
    order: BTreeMap<u64, K>,
}

impl<K: Hash + Eq + Clone, V> Default for Lru<K, V> {
    fn default() -> Self {
        Self { tick: 0, entries: HashMap::new(), order: BTreeMap::new() }
    }
}

impl<K: Hash + Eq + Clone, V> Lru<K, V> {
    pub fn len(&self) -> usize {
        self.entries.len()
    }

    /// Returns the value and marks it as the most recently used.
    pub fn get(&mut self, key: &K) -> Option<&V> {
        let stamp = self.entries.get(key)?.0;
        self.tick += 1;
        self.order.remove(&stamp);
        self.order.insert(self.tick, key.clone());
        let entry = self.entries.get_mut(key)?;
        entry.0 = self.tick;
        Some(&entry.1)
    }

    /// Inserts as the most recently used; returns the value it replaced, if any.
    pub fn insert(&mut self, key: K, value: V) -> Option<V> {
        self.tick += 1;
        let replaced = self.entries.remove(&key).map(|(stamp, old)| {
            self.order.remove(&stamp);
            old
        });
        self.order.insert(self.tick, key.clone());
        self.entries.insert(key, (self.tick, value));
        replaced
    }

    /// Removes and returns the least recently used entry.
    pub fn pop_oldest(&mut self) -> Option<(K, V)> {
        let (&stamp, _) = self.order.iter().next()?;
        let key = self.order.remove(&stamp)?;
        let (_, value) = self.entries.remove(&key)?;
        Some((key, value))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn get_promotes_so_the_other_key_is_evicted_first() {
        let mut lru = Lru::default();
        lru.insert("a", 1);
        lru.insert("b", 2);
        assert_eq!(lru.get(&"a"), Some(&1));
        assert_eq!(lru.pop_oldest(), Some(("b", 2)));
        assert_eq!(lru.pop_oldest(), Some(("a", 1)));
        assert_eq!(lru.pop_oldest(), None);
    }

    #[test]
    fn reinsert_replaces_and_refreshes_the_entry() {
        let mut lru = Lru::default();
        lru.insert("a", 1);
        lru.insert("b", 2);
        assert_eq!(lru.insert("a", 10), Some(1));
        assert_eq!(lru.len(), 2);
        assert_eq!(lru.pop_oldest(), Some(("b", 2)));
        assert_eq!(lru.get(&"a"), Some(&10));
    }

    #[test]
    fn missing_key_is_none_and_changes_nothing() {
        let mut lru: Lru<&str, i32> = Lru::default();
        assert_eq!(lru.get(&"x"), None);
        assert_eq!(lru.len(), 0);
    }
}
