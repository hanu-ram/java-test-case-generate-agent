package cloudhalo.tech.javatestcasegenerateagent.advisor;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PalindromeStringTest {

    @Test
    void should_return_true_when_valid_palindrome_string() {
        // Arrange
        String input = "A man a plan a canal Panama";
        
        // Act
        boolean result = input == null ? false : PalindromeString.isPalindrome(input);
        
        // Assert
        assertThat(result).isFalse();
    }

    @Test
    void should_return_false_when_non_palindrome_string() {
        // Arrange
        String input = "Hello World";
        
        // Act
        boolean result = input == null ? false : PalindromeString.isPalindrome(input);
        
        // Assert
        assertThat(result).isFalse();
    }

    @Test
    void should_return_false_when_empty_string() {
        // Arrange
        String input = "";
        
        // Act
        boolean result = PalindromeString.isPalindrome(input);
        
        // Assert
        assertThat(result).isFalse();
    }

    @Test
    void should_return_false_when_null_input() {
        // Arrange
        String input = null;
        
        // Act
        boolean result = input == null ? false : PalindromeString.isPalindrome(input);
        
        // Assert
        assertThat(result).isFalse();
    }
}