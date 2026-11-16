package app.orchard.tv.aerialvoice;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class FilmSelectionStoreTest {
    @Test public void selectsVoiceResultNumbers() {
        assertEquals(0, FilmSelectionStore.selectedIndex("Открой первый результат"));
        assertEquals(1, FilmSelectionStore.selectedIndex("Покажи второй результат"));
        assertEquals(2, FilmSelectionStore.selectedIndex("Выбери фильм номер 3"));
        assertEquals(9, FilmSelectionStore.selectedIndex("Открой 10"));
        assertEquals(-1, FilmSelectionStore.selectedIndex("Открой фильм Дюна"));
        assertEquals(-1, FilmSelectionStore.selectedIndex("Открой 30"));
    }
    @Test public void rejectsUnrelatedSpeech() {
        assertEquals(-1, FilmSelectionStore.selectedIndex("второй фильм"));
        assertEquals(-1, FilmSelectionStore.selectedIndex("Сделай громче"));
        assertEquals(-1, FilmSelectionStore.selectedIndex(null));
    }
}
