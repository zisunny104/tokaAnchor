package dev.toka.anchor.upkeep;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;

import cn.nukkit.command.data.CommandEnum;
import java.util.HashMap;
import java.util.List;
import org.junit.Test;

public class CommandViewTest {
    @Test public void sameNameWithOtherWordsGetsAnotherNameAndTheSameWordsKeepOne() {
        var owner = new HashMap<String, List<String>>();
        var renamed = new HashMap<String, CommandEnum>();
        var first = new CommandEnum("actionEnums", "help");
        var other = new CommandEnum("actionEnums", "lease");
        var again = new CommandEnum("actionEnums", "help");
        assertSame(first, CommandView.unique(first, owner, renamed));
        var moved = CommandView.unique(other, owner, renamed);
        assertNotEquals("actionEnums", moved.getName());
        assertEquals(List.of("lease"), moved.getValues());
        assertSame(moved, CommandView.unique(new CommandEnum("actionEnums", "lease"), owner, renamed));
        assertSame(again, CommandView.unique(again, owner, renamed));
    }
}
