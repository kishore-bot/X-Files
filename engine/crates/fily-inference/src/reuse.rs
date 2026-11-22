//! Which part of a prompt the KV cache already holds. Pure, so it is tested
//! without a model; the Android worker applies the result to llama.cpp.

/// Length of the longest common prefix of two token sequences.
pub fn common_prefix_len<T: PartialEq>(cached: &[T], next: &[T]) -> usize {
    cached.iter().zip(next).take_while(|(a, b)| a == b).count()
}

/// How many leading tokens of `next` the cache can keep. One token is always left to
/// decode, because the logits for the next position come from the last decoded token.
pub fn reusable_len<T: PartialEq>(cached: &[T], next: &[T]) -> usize {
    common_prefix_len(cached, next).min(next.len().saturating_sub(1))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn common_prefix_stops_at_the_first_difference() {
        assert_eq!(common_prefix_len(&[1, 2, 3, 4], &[1, 2, 9, 4]), 2);
        assert_eq!(common_prefix_len(&[1, 2], &[1, 2, 3]), 2);
        assert_eq!(common_prefix_len::<i32>(&[], &[1]), 0);
        assert_eq!(common_prefix_len(&[5], &[6]), 0);
    }

    #[test]
    fn an_extension_reuses_the_whole_cache() {
        let cached = [1, 2, 3];
        assert_eq!(reusable_len(&cached, &[1, 2, 3, 4, 5]), 3);
    }

    #[test]
    fn an_identical_prompt_still_decodes_its_last_token() {
        assert_eq!(reusable_len(&[1, 2, 3], &[1, 2, 3]), 2);
        assert_eq!(reusable_len(&[7], &[7]), 0);
    }

    #[test]
    fn a_divergence_keeps_only_the_shared_head() {
        // A later turn that changes the tool list rewrites the system block from that point on.
        let cached = [10, 11, 12, 13, 14];
        let next = [10, 11, 99, 13];
        assert_eq!(reusable_len(&cached, &next), 2);
    }

    #[test]
    fn an_empty_prompt_reuses_nothing() {
        assert_eq!(reusable_len(&[1, 2], &[] as &[i32]), 0);
    }
}
