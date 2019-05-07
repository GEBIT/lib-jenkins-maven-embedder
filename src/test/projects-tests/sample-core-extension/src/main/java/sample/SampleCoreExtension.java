package sample;


import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.MavenExecutionException;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.logging.Logger;

@Component( role = AbstractMavenLifecycleParticipant.class, hint = "default" )
public class SampleCoreExtension
	extends AbstractMavenLifecycleParticipant
{
    @Requirement
    private Logger logger;

    @Override
    public void afterSessionStart(MavenSession session) throws MavenExecutionException {
	    session.getUserProperties().put("rev", "1.0.0.sample");
	    logger.info("Setting sample ${rev} = 1.0.0.sample");
    }
}
