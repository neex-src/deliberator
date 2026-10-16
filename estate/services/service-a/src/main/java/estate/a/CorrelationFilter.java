package estate.a;

import estate.common.Correlation;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Reads X-Correlation-Id (or makes one), keeps it in the MDC for the request and echoes it back. */
@Component
public class CorrelationFilter extends OncePerRequestFilter {
  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    try (var scope = Correlation.open(req.getHeader(Correlation.HEADER))) {
      res.setHeader(Correlation.HEADER, scope.id);
      chain.doFilter(req, res);
    }
  }
}
